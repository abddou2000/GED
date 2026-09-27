package com.ipt.ged.recherche;

import com.ipt.ged.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SearchIndexer} natif PostgreSQL (§4.4) sur {@code document_texte}.
 *
 * <ul>
 *   <li>vecteur : {@code ged_document_tsvector(texte)} =
 *       {@code to_tsvector('french', unaccent) || to_tsvector('arabic', normalisé)} ;</li>
 *   <li>requête : {@code ged_requete_texte(q)} = {@code websearch_to_tsquery}
 *       française OU arabe, avec les mêmes normalisations ;</li>
 *   <li>tri {@code ts_rank_cd}, total par fenêtre {@code count(*) OVER ()} sur
 *       le périmètre autorisé, pagination ;</li>
 *   <li>extraits {@code ts_headline} calculés <b>après</b> filtre et pagination,
 *       sur les seules lignes de la page.</li>
 * </ul>
 */
public class SearchIndexerPostgres implements SearchIndexer {

    /** Marqueurs de surlignage : caractères à usage privé, retirés du texte indexé. */
    static final char DEBUT = '\uE000';
    static final char FIN = '\uE001';
    private static final String OPTIONS_EXTRAIT = "StartSel=" + DEBUT + ", StopSel=" + FIN
            + ", MaxWords=35, MinWords=12, MaxFragments=3, FragmentDelimiter=\" … \"";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate nomme;
    private final PredicatDroits droits;

    public SearchIndexerPostgres(JdbcTemplate jdbc, PredicatDroits droits) {
        this.jdbc = jdbc;
        this.nomme = new NamedParameterJdbcTemplate(jdbc);
        this.droits = droits;
    }

    @Override
    public void indexer(TexteAIndexer t) {
        String texte = t.texte() == null ? "" : t.texte().replace(String.valueOf(DEBUT), "").replace(String.valueOf(FIN), "");
        // Une seule ligne par document : celle de la version courante.
        jdbc.update("DELETE FROM document_texte WHERE document_id = ? AND version_id <> ?", t.documentId(), t.versionId());
        // Le texte n'est transmis qu'une fois (il peut peser plusieurs Mo) :
        // le vecteur est calculé côté base à partir de la même valeur.
        jdbc.update("WITH s(texte) AS (SELECT CAST(? AS text)) "
                        + "INSERT INTO document_texte (id, version_id, document_id, langue, texte, tsv, provenance, nb_pages, indexe_le) "
                        + "SELECT ?, ?, ?, ?, s.texte, ged_document_tsvector(s.texte), ?, ?, now() FROM s "
                        + "ON CONFLICT (version_id) DO UPDATE SET langue = EXCLUDED.langue, texte = EXCLUDED.texte, "
                        + "tsv = EXCLUDED.tsv, provenance = EXCLUDED.provenance, nb_pages = EXCLUDED.nb_pages, indexe_le = now()",
                texte, UuidV7.suivant(), t.versionId(), t.documentId(), t.langue(), t.provenance(), t.nbPages());
    }

    @Override
    public boolean supprimer(UUID documentId) {
        return jdbc.update("DELETE FROM document_texte WHERE document_id = ?", documentId) > 0;
    }

    @Override
    public PageResultats rechercher(RequeteRecherche r, Authentication utilisateur) {
        if (r.texte() == null || r.texte().isBlank()) {
            return new PageResultats(List.of(), 0, r.page(), r.taille());
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("q", r.texte().strip())
                .addValue("taille", r.taille())
                .addValue("decalage", (long) r.page() * r.taille())
                .addValue("options", OPTIONS_EXTRAIT);
        // Corbeille exclue : un document supprimé est invisible de tous ses
        // emplacements (§12.5), recherche comprise.
        StringBuilder where = new StringBuilder("dt.tsv @@ q.requete AND NOT d.supprime");
        List<FragmentSql> fragments = new ArrayList<>();
        fragments.add(droits.predicat("dt.document_id", utilisateur));
        fragments.addAll(r.filtres());
        for (FragmentSql f : fragments) {
            where.append(" AND (").append(f.sql()).append(')');
            for (Map.Entry<String, Object> e : f.parametres().entrySet()) {
                if (p.hasValue(e.getKey())) {
                    throw new IllegalArgumentException("Paramètre SQL en double : " + e.getKey());
                }
                p.addValue(e.getKey(), e.getValue());
            }
        }
        // Colonnes de tri en liste blanche : jamais de texte de l'appelant dans l'ORDER BY.
        String ordre = switch (r.tri()) {
            case INDEXATION_RECENTE -> "indexe_le DESC";
            case DATE_DEPOT -> "cree_le DESC NULLS LAST";
            case NOM -> "lower(nom) ASC";
            case TYPE -> "lower(type_document) ASC, rang DESC";
            case PERTINENCE -> "rang DESC";
        };
        String depuis = "FROM document_texte dt "
                + "JOIN document d ON d.id = dt.document_id "
                + "LEFT JOIN type_document t ON t.id = d.type_document_id "
                + "LEFT JOIN noeud w ON w.id = d.noeud_principal_id "
                + "CROSS JOIN (SELECT ged_requete_texte(:q) AS requete) q ";
        String sql = "SELECT p.*, "
                + "ts_headline('ged_francais', ged_normaliser_arabe(dt.texte), "
                + "            websearch_to_tsquery('ged_francais', ged_normaliser_arabe(:q)), :options) AS extrait "
                + "FROM (SELECT * FROM (SELECT dt.document_id, dt.version_id, dt.indexe_le, d.name AS nom, "
                + "             d.created_at AS cree_le, t.type_de_document AS type_document, w.name AS espace, "
                + "             d.statut_conservation, d.canal_depot, "
                + "             ts_rank_cd(dt.tsv, q.requete) AS rang, count(*) OVER () AS total "
                + "      " + depuis + "WHERE " + where + ") a "
                + "      ORDER BY " + ordre + ", version_id "
                + "      LIMIT :taille OFFSET :decalage) p "
                + "JOIN document_texte dt ON dt.version_id = p.version_id "
                + "ORDER BY " + ordre.replace("lower(nom)", "lower(p.nom)").replace("lower(type_document)", "lower(p.type_document)")
                        .replace("indexe_le", "p.indexe_le").replace("cree_le", "p.cree_le").replace("rang", "p.rang")
                + ", p.version_id";
        long[] total = {-1};
        List<PageResultats.Resultat> resultats = nomme.query(sql, p, (rs, i) -> {
            total[0] = rs.getLong("total");
            java.time.OffsetDateTime cree = rs.getObject("cree_le", java.time.OffsetDateTime.class);
            return new PageResultats.Resultat(rs.getObject("document_id", UUID.class),
                    rs.getObject("version_id", UUID.class), rs.getDouble("rang"), segments(rs.getString("extrait")),
                    rs.getString("nom"), rs.getString("type_document"), rs.getString("espace"),
                    cree != null ? cree.toInstant() : null, rs.getString("statut_conservation"),
                    rs.getString("canal_depot"));
        });
        if (total[0] < 0) {
            // Page au-delà de la fin : le total n'a pas pu être lu sur une ligne.
            total[0] = r.page() == 0 ? 0 : nomme.queryForObject("SELECT COUNT(*) " + depuis + "WHERE " + where, p, Long.class);
        }
        return new PageResultats(resultats, total[0], r.page(), r.taille());
    }

    /** Découpe l'extrait selon les marqueurs de surlignage. */
    static List<PageResultats.Segment> segments(String extrait) {
        List<PageResultats.Segment> s = new ArrayList<>();
        if (extrait == null) return s;
        StringBuilder courant = new StringBuilder();
        boolean surligne = false;
        for (int i = 0; i < extrait.length(); i++) {
            char c = extrait.charAt(i);
            if (c == DEBUT || c == FIN) {
                if (courant.length() > 0) s.add(new PageResultats.Segment(courant.toString(), surligne));
                courant.setLength(0);
                surligne = c == DEBUT;
            } else {
                courant.append(c);
            }
        }
        if (courant.length() > 0) s.add(new PageResultats.Segment(courant.toString(), surligne));
        return s;
    }

    @Override
    public List<UUID> versionsIndexees(List<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) return List.of();
        return nomme.queryForList("SELECT version_id FROM document_texte WHERE version_id IN (:ids)",
                new MapSqlParameterSource("ids", versionIds), UUID.class);
    }

    @Override
    public java.util.Optional<TexteIndexe> texte(UUID documentId) {
        return jdbc.query("SELECT document_id, version_id, langue, provenance, nb_pages, indexe_le, texte "
                        + "FROM document_texte WHERE document_id = ?",
                (rs, i) -> new TexteIndexe(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class),
                        rs.getString("langue"), rs.getString("provenance"), (Integer) rs.getObject("nb_pages"),
                        rs.getObject("indexe_le", java.time.OffsetDateTime.class).toInstant(), rs.getString("texte")),
                documentId).stream().findFirst();
    }

    @Override
    public long compter() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM document_texte", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<UUID> reindexerLot(UUID apres, int taille) {
        // Liste lue dans l'ordre de la base : c'est elle qui fixe le curseur,
        // l'ordre des UUID en Java (entiers signés) ne coïncide pas.
        List<UUID> lot = apres == null
                ? jdbc.queryForList("SELECT version_id FROM document_texte ORDER BY version_id LIMIT ?", UUID.class, taille)
                : jdbc.queryForList("SELECT version_id FROM document_texte WHERE version_id > ? ORDER BY version_id LIMIT ?",
                        UUID.class, apres, taille);
        if (!lot.isEmpty()) {
            nomme.update("UPDATE document_texte SET tsv = ged_document_tsvector(texte) "
                    + "WHERE version_id IN (:ids)", new MapSqlParameterSource("ids", lot));
        }
        return lot;
    }
}
