package com.ipt.ged.document.recherche;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.erreur.ChampsInconnusRefuses;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.document.DocumentService;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.planindexation.metamodele.ChampPlan;
import com.ipt.ged.planindexation.metamodele.ValeursMetadonnees;
import com.ipt.ged.recherche.CriteresDocument;
import com.ipt.ged.recherche.FragmentSql;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recherche sur métadonnées (dossier technique §12.7) : critères par index,
 * sur le JSONB {@code document.metadonnees}, en SQL natif pour que les index
 * servent :
 * <ul>
 *   <li>liste et booléen : containment {@code metadonnees @> {"CODE": valeur}},
 *       servi par l'index GIN ;</li>
 *   <li>date et nombre : bornes sur {@code meta_date(...)} / {@code meta_nombre(...)},
 *       servies par les index d'expression déclarés pour les champs fréquents ;</li>
 *   <li>texte : contient, insensible à la casse.</li>
 * </ul>
 * <b>La date du document est la clé de tri prioritaire</b>, puis l'identifiant.
 * Le périmètre est celui du point d'application unique (prédicat SQL
 * d'{@link AccessPredicate}, confidentialité comprise) : un document hors
 * périmètre n'entre ni dans les résultats ni dans le total. Les documents
 * archivés sont inclus par défaut, avec un filtre pour les inclure ou les
 * exclure (§12.6). Critères imposés du §4.4.3 portés par le document : plage
 * de date du document, confidentialité, déposant ({@link CriteresDocument}).
 * Un champ inconnu du corps est refusé (400 {@code PARAMETRE_INCONNU},
 * ANO-F-011) au lieu d'être ignoré.
 */
@Service
public class RechercheMetadonnees {

    /** Un critère : {@code valeur} (texte, liste, booléen) ou bornes {@code de} / {@code a} (date, nombre). */
    @ChampsInconnusRefuses
    public record Critere(String code, String valeur, String de, String a) {}

    /**
     * @param statutConservation ACTIF ou ARCHIVE pour filtrer ; absent = les deux
     * @param echeanceDepassee   vrai : documents dont l'échéance de conservation est atteinte (§12.9)
     * @param dateDocumentDu     borne basse incluse de la date du document (§4.4.3)
     * @param dateDocumentAu     borne haute incluse de la date du document (§4.4.3)
     * @param confidentialite    PUBLIC, PRIVE ou CONFIDENTIEL (§4.4.3), dans le périmètre autorisé
     * @param deposantUtilisateurId identité GED du déposant (§4.4.3)
     */
    @ChampsInconnusRefuses
    public record Requete(UUID typeDocumentId, UUID noeudId, String texte, List<Critere> criteres,
                          String statutConservation, Integer page, Integer size, Boolean echeanceDepassee,
                          LocalDate dateDocumentDu, LocalDate dateDocumentAu, Confidentialite confidentialite,
                          UUID deposantUtilisateurId) {}

    private final NamedParameterJdbcTemplate nomme;
    private final JdbcTemplate jdbc;
    private final AccessPredicate droits;
    private final UploadDocumentRepository documents;
    private final DocumentService service;
    private final ObjectMapper json;

    public RechercheMetadonnees(JdbcTemplate jdbc, AccessPredicate droits, UploadDocumentRepository documents,
                                DocumentService service, ObjectMapper json) {
        this.jdbc = jdbc;
        this.nomme = new NamedParameterJdbcTemplate(jdbc);
        this.droits = droits;
        this.documents = documents;
        this.service = service;
        this.json = json;
    }

    /**
     * Tris proposés par l'écran de recherche (ANO-F-010) : clé de l'API vers
     * expression SQL. Liste blanche : une clé inconnue est refusée (400) plutôt
     * qu'ignorée en silence.
     */
    private static final Map<String, String> TRIS = Map.of(
            "dateDocument", "d.date_document",
            "name", "lower(d.name)",
            "createdAt", "d.created_at");

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> rechercher(Requete r) {
        return rechercher(r, null, null);
    }

    /**
     * @param sortBy  {@code dateDocument} (défaut), {@code name} ou {@code createdAt}
     * @param sortDir {@code desc} (défaut) ou {@code asc} ; l'identifiant départage les ex-aequo
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> rechercher(Requete r, String sortBy, String sortDir) {
        String ordre = ordre(sortBy, sortDir);
        int page = r.page() == null ? 0 : Math.max(0, r.page());
        int taille = r.size() == null || r.size() <= 0 ? Tri.TAILLE_DEFAUT : Math.min(r.size(), Tri.TAILLE_MAX);

        MapSqlParameterSource p = new MapSqlParameterSource();
        FragmentSql perimetre = droits.predicatSql("d.id", SecurityContextHolder.getContext().getAuthentication(),
                CodePermission.CONSULTER);
        p.addValues(perimetre.parametres());
        StringBuilder ou = new StringBuilder(" FROM document d WHERE ").append(perimetre.sql());

        if (r.typeDocumentId() != null) {
            ou.append(" AND d.type_document_id = :type");
            p.addValue("type", r.typeDocumentId());
        }
        if (r.noeudId() != null) {
            ou.append(" AND (d.noeud_principal_id = :noeud OR EXISTS (SELECT 1 FROM document_rattachement r")
              .append(" WHERE r.document_id = d.id AND r.noeud_id = :noeud))");
            p.addValue("noeud", r.noeudId());
        }
        if (r.texte() != null && !r.texte().isBlank()) {
            ou.append(" AND (lower(d.name) LIKE :texte ESCAPE '\\' OR lower(coalesce(d.objet, '')) LIKE :texte ESCAPE '\\')");
            p.addValue("texte", contient(r.texte()));
        }
        if (r.statutConservation() != null && !r.statutConservation().isBlank()) {
            String s = r.statutConservation().trim().toUpperCase();
            if (!s.equals("ACTIF") && !s.equals("ARCHIVE")) {
                throw new IllegalArgumentException("statutConservation : ACTIF ou ARCHIVE");
            }
            ou.append(" AND d.statut_conservation = :statut");
            p.addValue("statut", s);
        }
        if (Boolean.TRUE.equals(r.echeanceDepassee())) {
            ou.append(" AND d.echeance_conservation <= :echeance");
            p.addValue("echeance", com.ipt.ged.document.conservation.Echeances.aujourdhui());
        }
        for (FragmentSql f : new CriteresDocument(r.dateDocumentDu(), r.dateDocumentAu(), r.confidentialite(),
                r.deposantUtilisateurId()).fragments()) {
            ou.append(" AND ").append(f.sql());
            p.addValues(f.parametres());
        }
        List<Critere> criteres = r.criteres() == null ? List.of() : r.criteres();
        for (int i = 0; i < criteres.size(); i++) {
            ajouter(ou, p, criteres.get(i), i);
        }

        Long total = nomme.queryForObject("SELECT count(*)" + ou, p, Long.class);
        p.addValue("taille", taille).addValue("decalage", (long) page * taille);
        List<UUID> ids = nomme.queryForList("SELECT d.id" + ou
                + " ORDER BY " + ordre + " LIMIT :taille OFFSET :decalage", p, UUID.class);

        Map<UUID, UploadDocument> parId = new HashMap<>();
        documents.findAllById(ids).forEach(d -> parId.put(d.getId(), d));
        List<UploadDocument> ordonnes = ids.stream().map(parId::get).filter(java.util.Objects::nonNull).toList();
        return service.pageDe(new PageImpl<>(ordonnes, PageRequest.of(page, taille), total == null ? 0 : total));
    }

    /** Clause ORDER BY issue de la liste blanche ; date du document décroissante par défaut. */
    static String ordre(String sortBy, String sortDir) {
        String cle = sortBy == null || sortBy.isBlank() ? "dateDocument" : sortBy.trim();
        String colonne = TRIS.get(cle);
        if (colonne == null) {
            throw new IllegalArgumentException("Tri inconnu : " + cle + " (dateDocument, name ou createdAt)");
        }
        String sens = sortDir == null || sortDir.isBlank() ? "desc" : sortDir.trim().toLowerCase();
        if (!sens.equals("asc") && !sens.equals("desc")) {
            throw new IllegalArgumentException("Sens de tri : asc ou desc");
        }
        String s = sens.toUpperCase();
        return colonne + " " + s + ", d.id " + s;
    }

    private void ajouter(StringBuilder ou, MapSqlParameterSource p, Critere c, int i) {
        if (c == null || c.code() == null || c.code().isBlank()) {
            throw new IllegalArgumentException("Chaque critère désigne un index par son code.");
        }
        List<ChampPlan> trouves = jdbc.query("SELECT id, code, nom_index, type_champs, obligatoire, valeurs,"
                        + " valeur_par_defaut, indexe_pour_recherche FROM index_def WHERE lower(code) = lower(?) AND NOT supprime",
                (rs, n) -> new ChampPlan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)), c.code().trim());
        if (trouves.isEmpty()) throw new IllegalArgumentException("Index inconnu : " + c.code());
        ChampPlan champ = trouves.get(0);
        String cle = "c" + i;
        p.addValue(cle, champ.code());
        switch (champ.natureTypee()) {
            case DATE -> {
                LocalDate de = borneDate(c.de(), champ), a = borneDate(c.a(), champ);
                if (de != null) { ou.append(" AND meta_date(d.metadonnees, :").append(cle).append(") >= :").append(cle).append("de"); p.addValue(cle + "de", de); }
                if (a != null) { ou.append(" AND meta_date(d.metadonnees, :").append(cle).append(") <= :").append(cle).append("a"); p.addValue(cle + "a", a); }
                if (de == null && a == null) ou.append(" AND meta_date(d.metadonnees, :").append(cle).append(") IS NOT NULL");
            }
            case NOMBRE -> {
                BigDecimal de = borneNombre(c.de(), champ), a = borneNombre(c.a(), champ);
                if (de != null) { ou.append(" AND meta_nombre(d.metadonnees, :").append(cle).append(") >= :").append(cle).append("de"); p.addValue(cle + "de", de); }
                if (a != null) { ou.append(" AND meta_nombre(d.metadonnees, :").append(cle).append(") <= :").append(cle).append("a"); p.addValue(cle + "a", a); }
                if (de == null && a == null) ou.append(" AND meta_nombre(d.metadonnees, :").append(cle).append(") IS NOT NULL");
            }
            case LISTE, BOOLEEN -> {
                Object valeur = valeurExacte(champ, c.valeur());
                ou.append(" AND d.metadonnees @> CAST(:").append(cle).append("j AS jsonb)");
                p.addValue(cle + "j", jsonDe(Map.of(champ.code(), valeur)));
            }
            default -> {
                if (c.valeur() == null || c.valeur().isBlank()) {
                    ou.append(" AND meta_texte(d.metadonnees, :").append(cle).append(") IS NOT NULL");
                } else {
                    ou.append(" AND lower(meta_texte(d.metadonnees, :").append(cle).append(")) LIKE :")
                      .append(cle).append("v ESCAPE '\\'");
                    p.addValue(cle + "v", contient(c.valeur()));
                }
            }
        }
    }

    private static Object valeurExacte(ChampPlan champ, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalArgumentException("« " + champ.code() + " » : valeur attendue.");
        }
        if (champ.natureTypee() == com.ipt.ged.index.IndexFieldType.BOOLEEN) {
            Boolean b = ValeursMetadonnees.booleen(valeur);
            if (b == null) throw new IllegalArgumentException("« " + champ.code() + " » attend oui ou non.");
            return b;
        }
        // La valeur stockée est l'option telle que le plan l'écrit.
        return champ.options().stream().filter(o -> o.equalsIgnoreCase(valeur.trim())).findFirst()
                .orElse(valeur.trim());
    }

    private static LocalDate borneDate(String v, ChampPlan champ) {
        if (v == null || v.isBlank()) return null;
        LocalDate d = ValeursMetadonnees.date(v);
        if (d == null) throw new IllegalArgumentException("« " + champ.code() + " » : date AAAA-MM-JJ attendue.");
        return d;
    }

    private static BigDecimal borneNombre(String v, ChampPlan champ) {
        if (v == null || v.isBlank()) return null;
        BigDecimal n = ValeursMetadonnees.nombre(v);
        if (n == null) throw new IllegalArgumentException("« " + champ.code() + " » : nombre attendu.");
        return n;
    }

    /** Motif LIKE « contient », jokers de l'utilisateur neutralisés. */
    private static String contient(String v) {
        String s = v.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + s + "%";
    }

    private String jsonDe(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
