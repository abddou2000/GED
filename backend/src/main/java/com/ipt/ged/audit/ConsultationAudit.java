package com.ipt.ged.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.erreur.RegleMetierException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consultation filtrée et export du journal d'audit (DAT §7.4.3).
 *
 * <p>Toute consultation et tout export sont eux-mêmes tracés
 * ({@code AUDIT_CONSULTE}, {@code AUDIT_EXPORTE}) avec les critères employés.
 * Il n'y a, ici comme ailleurs, aucune opération de modification ni de
 * suppression (revue technique D11).
 *
 * <p>L'export est accompagné des scellements des périodes couvertes et de sa
 * propre empreinte SHA-256, pour établir l'opposabilité (Article 49.11) : un
 * tiers peut recalculer l'empreinte du fichier reçu et confronter les
 * scellements à leur copie hors base.
 */
@Service
public class ConsultationAudit {

    static final int TAILLE_MAX = 200;

    private static final String COLONNES = """
            id, horodatage, acteur_utilisateur_id, acteur_application_id, acteur_nom, adresse_ip, action,
            objet_type, objet_id, avant::text AS avant, apres::text AS apres, resultat, motif, trace_id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final GardeConsultationAudit garde;
    private final int exportMax;

    public ConsultationAudit(JdbcTemplate jdbc, ObjectMapper json, AuditService audit, GardeConsultationAudit garde,
                             @Value("${ged.audit.export.lignes-max:100000}") int exportMax) {
        this.jdbc = jdbc;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.audit = audit;
        this.garde = garde;
        this.exportMax = exportMax;
    }

    /** Page d'enregistrements, du plus récent au plus ancien. */
    public PageResponse<LigneAudit> rechercher(FiltreAudit filtre, int page, int taille) {
        garde.verifier();
        int t = Math.max(1, Math.min(taille, TAILLE_MAX));
        int p = Math.max(0, page);
        FiltreAudit.Clause c = filtre.clause();
        Long total = jdbc.queryForObject("SELECT count(*) FROM journal_audit" + c.sql(), Long.class, c.parametres());
        List<Object> params = new ArrayList<>(List.of(c.parametres()));
        params.add(t);
        params.add((long) p * t);
        List<LigneAudit> lignes = jdbc.query("SELECT " + COLONNES + " FROM journal_audit" + c.sql()
                + " ORDER BY id DESC LIMIT ? OFFSET ?", lecteur(), params.toArray());
        long n = total == null ? 0 : total;
        audit.enregistrer(EntreeAudit.de(ActionAudit.AUDIT_CONSULTE)
                .avecApres(avec(filtre.commeValeurs(), "page", p, "resultats", lignes.size())));
        return new PageResponse<>(lignes, n, p, t, (int) ((n + t - 1) / t));
    }

    /** Scellements, du plus récent au plus ancien. */
    public PageResponse<Map<String, Object>> scellements(int page, int taille) {
        garde.verifier();
        int t = Math.max(1, Math.min(taille, TAILLE_MAX));
        int p = Math.max(0, page);
        Long total = jdbc.queryForObject("SELECT count(*) FROM journal_audit_scellement", Long.class);
        List<Map<String, Object>> lignes = jdbc.query("""
                SELECT periode_debut, periode_fin, nombre, premier_numero, dernier_numero,
                       empreinte_precedente, empreinte, scelle_le
                  FROM journal_audit_scellement ORDER BY periode_debut DESC LIMIT ? OFFSET ?""",
                (rs, i) -> scellement(rs), t, (long) p * t);
        long n = total == null ? 0 : total;
        return new PageResponse<>(lignes, n, p, t, (int) ((n + t - 1) / t));
    }

    /** Fichier exporté : contenu, type et empreinte SHA-256 du contenu. */
    public record Export(byte[] contenu, String typeContenu, String nomFichier, String empreinte, int lignes) {}

    public Export exporter(FiltreAudit filtre, String format) {
        garde.verifier();
        boolean csv = "csv".equalsIgnoreCase(format);
        if (!csv && !"json".equalsIgnoreCase(format)) {
            throw new com.ipt.ged.common.erreur.RequeteInvalideException("Format d'export : csv ou json.");
        }
        FiltreAudit.Clause c = filtre.clause();
        Long total = jdbc.queryForObject("SELECT count(*) FROM journal_audit" + c.sql(), Long.class, c.parametres());
        if (total != null && total > exportMax) {
            throw new RegleMetierException("EXPORT_TROP_VOLUMINEUX",
                    "La sélection compte " + total + " enregistrements (maximum " + exportMax
                            + ") : restreignez la période ou les critères.");
        }
        List<LigneAudit> lignes = jdbc.query("SELECT " + COLONNES + " FROM journal_audit" + c.sql()
                + " ORDER BY id", lecteur(), c.parametres());
        List<Map<String, Object>> scelles = scellementsCouvrant(lignes);
        Instant le = Instant.now();

        byte[] contenu = csv ? enCsv(filtre, lignes, scelles, le) : enJson(filtre, lignes, scelles, le);
        String empreinte = sha256(contenu);
        String nom = "journal-audit-" + le.toString().replace(":", "").replace(".", "") + (csv ? ".csv" : ".json");
        audit.enregistrer(EntreeAudit.de(ActionAudit.AUDIT_EXPORTE)
                .avecApres(avec(filtre.commeValeurs(), "format", csv ? "csv" : "json",
                        "lignes", lignes.size(), "empreinte", empreinte)));
        return new Export(contenu, csv ? "text/csv;charset=UTF-8" : "application/json", nom, empreinte, lignes.size());
    }

    /* ------------------------------------------------------------------ formats */

    private byte[] enJson(FiltreAudit filtre, List<LigneAudit> lignes, List<Map<String, Object>> scelles, Instant le) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "ged-journal-audit-v1");
        doc.put("exporteLe", le.toString());
        doc.put("filtre", filtre.commeValeurs());
        doc.put("scellements", scelles);
        doc.put("evenements", lignes);
        try {
            return json.writeValueAsBytes(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * CSV (RFC 4180, séparateur « ; » pour les tableurs français) : en-tête de
     * commentaires avec le filtre et les scellements, puis une ligne par
     * enregistrement.
     */
    private byte[] enCsv(FiltreAudit filtre, List<LigneAudit> lignes, List<Map<String, Object>> scelles, Instant le) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Journal d'audit GED Marchica Med, exporté le ").append(le).append('\n');
        sb.append("# Filtre : ").append(filtre.commeValeurs()).append('\n');
        for (Map<String, Object> s : scelles) {
            sb.append("# Scellement ").append(s.get("periodeDebut")).append(" / ").append(s.get("periodeFin"))
                    .append(" : ").append(s.get("empreinte")).append('\n');
        }
        sb.append("id;horodatage;acteur_utilisateur_id;acteur_application_id;acteur_nom;adresse_ip;action;"
                + "objet_type;objet_id;avant;apres;resultat;motif;trace_id\n");
        for (LigneAudit l : lignes) {
            sb.append(l.id()).append(';').append(l.horodatage()).append(';')
                    .append(cellule(l.acteurUtilisateurId())).append(';').append(cellule(l.acteurApplicationId())).append(';')
                    .append(cellule(l.acteurNom())).append(';').append(cellule(l.adresseIp())).append(';')
                    .append(cellule(l.action())).append(';').append(cellule(l.objetType())).append(';')
                    .append(cellule(l.objetId())).append(';').append(cellule(l.avant())).append(';')
                    .append(cellule(l.apres())).append(';').append(cellule(l.resultat())).append(';')
                    .append(cellule(l.motif())).append(';').append(cellule(l.traceId())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /* Guillemets doublés ; neutralisation des formules (=, +, -, @) qu'un tableur
       exécuterait à l'ouverture (injection CSV). */
    private static String cellule(Object valeur) {
        if (valeur == null) return "";
        String v = valeur.toString();
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /* --------------------------------------------------------------- outillage */

    private List<Map<String, Object>> scellementsCouvrant(List<LigneAudit> lignes) {
        if (lignes.isEmpty()) return List.of();
        Instant min = lignes.stream().map(LigneAudit::horodatage).min(Instant::compareTo).orElseThrow();
        Instant max = lignes.stream().map(LigneAudit::horodatage).max(Instant::compareTo).orElseThrow();
        return jdbc.query("""
                SELECT periode_debut, periode_fin, nombre, premier_numero, dernier_numero,
                       empreinte_precedente, empreinte, scelle_le
                  FROM journal_audit_scellement
                 WHERE periode_fin > ? AND periode_debut <= ? ORDER BY periode_debut""",
                (rs, i) -> scellement(rs), Timestamp.from(min), Timestamp.from(max));
    }

    private static Map<String, Object> scellement(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodeDebut", rs.getTimestamp("periode_debut").toInstant().toString());
        m.put("periodeFin", rs.getTimestamp("periode_fin").toInstant().toString());
        m.put("nombre", rs.getLong("nombre"));
        m.put("empreintePrecedente", rs.getString("empreinte_precedente").trim());
        m.put("empreinte", rs.getString("empreinte").trim());
        m.put("scelleLe", rs.getTimestamp("scelle_le").toInstant().toString());
        return m;
    }

    private RowMapper<LigneAudit> lecteur() {
        return (rs, i) -> new LigneAudit(rs.getLong("id"), rs.getTimestamp("horodatage").toInstant(),
                rs.getObject("acteur_utilisateur_id", UUID.class), rs.getObject("acteur_application_id", UUID.class),
                rs.getString("acteur_nom"), rs.getString("adresse_ip"), rs.getString("action"),
                rs.getString("objet_type"), rs.getObject("objet_id", UUID.class),
                noeud(rs.getString("avant")), noeud(rs.getString("apres")), rs.getString("resultat"),
                rs.getString("motif"), rs.getObject("trace_id", UUID.class));
    }

    private JsonNode noeud(String texte) {
        if (texte == null) return null;
        try {
            return json.readTree(texte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> avec(Map<String, Object> base, Object... cleValeur) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        for (int i = 0; i + 1 < cleValeur.length; i += 2) m.put((String) cleValeur[i], cleValeur[i + 1]);
        return m;
    }

    static String sha256(byte[] contenu) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
