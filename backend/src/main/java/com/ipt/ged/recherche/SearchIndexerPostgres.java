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
 *   <li><b>ensemble classé borné</b> (R32, essais de charge §5.4) : les
 *       correspondances autorisées (droits et critères appliqués <b>avant</b>
 *       le classement) sont limitées à {@link #PLAFOND_PAR_DEFAUT} candidats ;
 *       pour un tri par date, nom, type ou indexation, ce sont les premiers
 *       selon ce tri (résultat exact) ; pour la pertinence, les premiers
 *       trouvés par l'index, classés par {@code ts_rank} (sans positions,
 *       moins coûteux que {@code ts_rank_cd}) ;</li>
 *   <li><b>total plafonné</b> : exact jusqu'au plafond, « plus de N » au-delà
 *       ({@link PageResultats#totalPlafonne()}), au lieu d'un
 *       {@code count(*) OVER ()} qui lisait et classait toutes les
 *       correspondances (10 à 40 s pour un terme fréquent à 50 000 documents) ;</li>
 *   <li>extraits {@code ts_headline} calculés <b>après</b> filtre et pagination,
 *       sur les seules lignes de la page, et sur les
 *       {@link #EXTRAIT_MAX_CARACTERES} premiers caractères du texte (un
 *       attachement de 800 pages en compte plus d'un million).</li>
 * </ul>
 */
public class SearchIndexerPostgres implements SearchIndexer {

    /** Marqueurs de surlignage : caractères à usage privé, retirés du texte indexé. */
    static final char DEBUT = '\uE000';
    static final char FIN = '\uE001';
    private static final String OPTIONS_EXTRAIT = "StartSel=" + DEBUT + ", StopSel=" + FIN
            + ", MaxWords=35, MinWords=12, MaxFragments=3, FragmentDelimiter=\" … \"";

    /** Nombre maximal de correspondances classées et comptées (§5.4 des essais de charge). */
    public static final int PLAFOND_PAR_DEFAUT = 5000;
    /** Longueur du texte sur laquelle l'extrait est cherché. */
    static final int EXTRAIT_MAX_CARACTERES = 32_768;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate nomme;
    private final PredicatDroits droits;
    private final int plafond;

    public SearchIndexerPostgres(JdbcTemplate jdbc, PredicatDroits droits) {
        this(jdbc, droits, PLAFOND_PAR_DEFAUT);
    }

    /** @param plafond nombre maximal de correspondances classées et comptées (au moins 1). */
    public SearchIndexerPostgres(JdbcTemplate jdbc, PredicatDroits droits, int plafond) {
        if (plafond < 1) throw new IllegalArgumentException("Le plafond de la recherche doit être au moins 1.");
        this.jdbc = jdbc;
        this.nomme = new NamedParameterJdbcTemplate(jdbc);
        this.droits = droits;
        this.plafond = plafond;
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
                .addValue("options", OPTIONS_EXTRAIT)
                // Un de plus que le plafond : sait dire « plus de N » sans tout compter.
                .addValue("plafond", plafond + 1)
                .addValue("plafond_classe", plafond)
                .addValue("extrait_max", EXTRAIT_MAX_CARACTERES);
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
        // « cles » : ordre de sélection des correspondances retenues sous le plafond
        // (pour la pertinence, un ordre stable : le rang n'est connu qu'après lecture du vecteur) ;
        // « ordre » : ordre de la page, sur les colonnes nommées de l'ensemble retenu.
        String cles = switch (r.tri()) {
            case INDEXATION_RECENTE -> "dt.indexe_le DESC, dt.version_id";
            case DATE_DOCUMENT -> "d.date_document DESC NULLS LAST, dt.document_id DESC, dt.version_id";
            case DATE_DEPOT -> "d.created_at DESC NULLS LAST, dt.version_id";
            case NOM -> "lower(d.name) ASC, dt.version_id";
            case TYPE -> "lower(t.type_de_document) ASC, dt.version_id";
            // Pas de rang connu avant lecture du vecteur : ordre stable quelconque. Sans
            // ORDER BY, la LIMIT pousse le planificateur vers un balayage de la table,
            // désastreux pour un terme rare (mesuré : 0,17 s → 1 s à 50 000 documents).
            case PERTINENCE -> "dt.version_id";
        };
        String ordre = switch (r.tri()) {
            case INDEXATION_RECENTE -> "indexe_le DESC";
            case DATE_DOCUMENT -> "date_document DESC NULLS LAST, document_id DESC";
            case DATE_DEPOT -> "cree_le DESC NULLS LAST";
            case NOM -> "lower(nom) ASC";
            case TYPE -> "lower(type_document) ASC, rang DESC";
            case PERTINENCE -> "rang DESC";
        };
        // Correspondances autorisées, critères compris, bornées AVANT tout classement :
        // le vecteur (dans le TOAST) n'est relu pour le rang que sur cet ensemble.
        // Rang de sélection « rn » : le candidat en sus du plafond ne sert qu'à savoir
        // qu'il y en a plus ; il n'est ni classé ni affiché.
        String correspondances = "SELECT dt.document_id, dt.version_id, row_number() OVER ("
                + "ORDER BY " + cles + ") AS rn FROM document_texte dt "
                + "JOIN document d ON d.id = dt.document_id "
                + "LEFT JOIN type_document t ON t.id = d.type_document_id "
                + "LEFT JOIN noeud w ON w.id = d.noeud_principal_id "
                + "CROSS JOIN q WHERE " + where + " ORDER BY rn" + " LIMIT :plafond";
        String sql = "WITH q AS MATERIALIZED (SELECT ged_requete_texte(:q) AS requete), "
                + "c AS MATERIALIZED (" + correspondances + "), "
                + "n AS (SELECT count(*) AS total FROM c), "
                + "p AS (SELECT * FROM (SELECT c.document_id, c.version_id, dt.indexe_le, d.name AS nom, "
                + "         d.created_at AS cree_le, d.date_document, t.type_de_document AS type_document, "
                + "         w.name AS espace, d.statut_conservation, d.canal_depot, d.echeance_conservation, "
                + "         ts_rank(dt.tsv, q.requete) AS rang "
                + "       FROM c JOIN document_texte dt ON dt.version_id = c.version_id "
                + "       JOIN document d ON d.id = c.document_id "
                + "       LEFT JOIN type_document t ON t.id = d.type_document_id "
                + "       LEFT JOIN noeud w ON w.id = d.noeud_principal_id CROSS JOIN q "
                + "       WHERE c.rn <= :plafond_classe) a "
                + "     ORDER BY " + ordre + ", version_id LIMIT :taille OFFSET :decalage) "
                + "SELECT * FROM (SELECT p.*, n.total, "
                + "  ts_headline('ged_francais', ged_normaliser_arabe(left(dt.texte, :extrait_max)), "
                + "              websearch_to_tsquery('ged_francais', ged_normaliser_arabe(:q)), :options) AS extrait "
                + "  FROM p JOIN document_texte dt ON dt.version_id = p.version_id CROSS JOIN n) r "
                + "ORDER BY " + ordre + ", version_id";
        long[] total = {-1};
        List<PageResultats.Resultat> resultats = nomme.query(sql, p, (rs, i) -> {
            total[0] = rs.getLong("total");
            java.time.OffsetDateTime cree = rs.getObject("cree_le", java.time.OffsetDateTime.class);
            return new PageResultats.Resultat(rs.getObject("document_id", UUID.class),
                    rs.getObject("version_id", UUID.class), rs.getDouble("rang"), segments(rs.getString("extrait")),
                    rs.getString("nom"), rs.getString("type_document"), rs.getString("espace"),
                    cree != null ? cree.toInstant() : null, rs.getString("statut_conservation"),
                    rs.getString("canal_depot"), com.ipt.ged.document.conservation.Echeances.depassee(
                            rs.getObject("echeance_conservation", java.time.LocalDate.class)),
                    rs.getObject("date_document", java.time.LocalDate.class));
        });
        if (total[0] < 0) {
            // Page au-delà de la fin : le total n'a pas pu être lu sur une ligne.
            total[0] = r.page() == 0 ? 0 : nomme.queryForObject("WITH q AS MATERIALIZED (SELECT ged_requete_texte(:q) "
                    + "AS requete) SELECT count(*) FROM (" + correspondances + ") c", p, Long.class);
        }
        boolean plafonne = total[0] > plafond;
        return new PageResultats(resultats, plafonne ? plafond : total[0], r.page(), r.taille(), plafonne);
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
