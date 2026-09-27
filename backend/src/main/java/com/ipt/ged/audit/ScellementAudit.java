package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scellement horaire chaîné du journal d'audit (DAT §7.4.2).
 *
 * <p>Pour chaque heure écoulée, une empreinte SHA-256 est calculée sur
 * l'empreinte de l'heure précédente, les bornes de la période et chaque
 * enregistrement de la période dans l'ordre des identifiants. Elle est inscrite
 * dans {@code journal_audit_scellement} (INSERT seul) <b>et</b> exportée hors de
 * la base : ligne ajoutée à un fichier ouvert en ajout seul (à placer sur un
 * support distinct, attribut {@code chattr +a}) et ligne du journal technique
 * {@code ged.audit.scellement}, relayé au journal centralisé. Toute
 * modification ultérieure d'un enregistrement, même par un superutilisateur,
 * rompt la chaîne à la vérification ({@link VerificationAudit}).
 *
 * <p>Une période n'est scellée qu'une fois close depuis une marge (5 minutes
 * par défaut) : une transaction encore ouverte au changement d'heure a le
 * temps de valider sa trace avant le calcul.
 *
 * <p>Encodage de l'empreinte (version 1) : préfixe {@link #VERSION}, puis
 * chaque valeur précédée de sa longueur en octets (−1 pour une valeur nulle).
 * L'encodage par longueur rend impossible de déplacer des caractères d'un champ
 * à l'autre sans changer l'empreinte.
 */
@Service
public class ScellementAudit {

    static final String VERSION = "GED-AUDIT-SCELLEMENT-V1";
    static final String EMPREINTE_INITIALE = "0".repeat(64);

    private static final Logger journal = LoggerFactory.getLogger(ScellementAudit.class);
    /** Journal technique dédié : copie hors base de chaque scellement (DAT §7.4.2). */
    private static final Logger journalScellements = LoggerFactory.getLogger("ged.audit.scellement");
    private static final long VERROU = 0x6765645f61756469L; // « ged_audi »
    private static final HexFormat HEX = HexFormat.of();

    /** Enregistrements d'une période, chaque valeur en texte, dans l'ordre des identifiants. */
    static final String LIGNES_PERIODE = """
            SELECT id::text, to_char(horodatage AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                   acteur_utilisateur_id::text, acteur_application_id::text, acteur_nom, adresse_ip,
                   action, objet_type, objet_id::text, avant::text, apres::text, resultat, motif,
                   trace_id::text
              FROM journal_audit
             WHERE horodatage >= ? AND horodatage < ?
             ORDER BY id""";
    private static final int COLONNES = 14;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Path export;
    private final Duration marge;
    private final Clock horloge;

    @Autowired
    public ScellementAudit(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json,
                           @Value("${ged.audit.scellement.fichier-export:./audit/scellements.jsonl}") String export,
                           @Value("${ged.audit.scellement.marge:5m}") Duration marge) {
        this(jdbc, transactions, json, Path.of(export), marge, Clock.systemUTC());
    }

    ScellementAudit(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json,
                    Path export, Duration marge, Clock horloge) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
        this.json = json;
        this.export = export.toAbsolutePath().normalize();
        this.marge = marge;
        this.horloge = horloge;
    }

    Path fichierExport() {
        return export;
    }

    /** Un scellement, tel qu'inscrit en base et exporté. */
    public record Scellement(Instant periodeDebut, Instant periodeFin, long nombre, Long premierNumero,
                             Long dernierNumero, String empreintePrecedente, String empreinte) {}

    /**
     * Scelle toutes les périodes closes non encore scellées, dans l'ordre.
     *
     * @return les scellements produits (vide si rien n'était à sceller ou si
     *         une autre instance scelle en ce moment)
     */
    public List<Scellement> scellerPeriodesCloses() {
        Instant limite = horloge.instant().minus(marge).truncatedTo(ChronoUnit.HOURS);
        List<Scellement> produits = new java.util.ArrayList<>();
        while (true) {
            Scellement s = transactions.execute(statut -> scellerSuivante(limite));
            if (s == null) break;
            produits.add(s);
        }
        return produits;
    }

    /** Scelle la période suivant le dernier scellement, si elle est close ; une transaction par période. */
    private Scellement scellerSuivante(Instant limite) {
        Boolean verrou = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, VERROU);
        if (!Boolean.TRUE.equals(verrou)) return null;

        List<Map<String, Object>> dernier = jdbc.queryForList(
                "SELECT periode_fin, empreinte FROM journal_audit_scellement ORDER BY periode_debut DESC LIMIT 1");
        Instant debut;
        String precedente;
        if (dernier.isEmpty()) {
            Timestamp premier = jdbc.queryForObject("SELECT min(horodatage) FROM journal_audit", Timestamp.class);
            debut = (premier != null ? premier.toInstant() : limite).truncatedTo(ChronoUnit.HOURS);
            precedente = EMPREINTE_INITIALE;
        } else {
            debut = ((Timestamp) dernier.get(0).get("periode_fin")).toInstant();
            precedente = ((String) dernier.get(0).get("empreinte")).trim();
        }
        Instant fin = debut.plus(1, ChronoUnit.HOURS);
        if (fin.isAfter(limite)) return null;

        Scellement s = calculer(debut, fin, precedente);
        jdbc.update("""
                INSERT INTO journal_audit_scellement (periode_debut, periode_fin, nombre, premier_numero,
                                                      dernier_numero, empreinte_precedente, empreinte)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                Timestamp.from(s.periodeDebut()), Timestamp.from(s.periodeFin()), s.nombre(),
                s.premierNumero(), s.dernierNumero(), s.empreintePrecedente(), s.empreinte());
        // Export avant la validation : un export impossible annule le scellement,
        // qui sera retenté à la prochaine exécution (jamais de scellement en base
        // sans sa copie hors base).
        exporter(s);
        return s;
    }

    /** Recalcule l'empreinte d'une période à partir des enregistrements présents en base. */
    public Scellement calculer(Instant debut, Instant fin, String precedente) {
        MessageDigest sha = sha256();
        champ(sha, VERSION);
        champ(sha, precedente);
        champ(sha, debut.toString());
        champ(sha, fin.toString());
        long[] compte = {0};
        Long[] bornes = {null, null};
        jdbc.query(LIGNES_PERIODE, rs -> {
            for (int i = 1; i <= COLONNES; i++) {
                champ(sha, rs.getString(i));
            }
            long id = Long.parseLong(rs.getString(1));
            if (bornes[0] == null) bornes[0] = id;
            bornes[1] = id;
            compte[0]++;
        }, Timestamp.from(debut), Timestamp.from(fin));
        champ(sha, Long.toString(compte[0]));
        return new Scellement(debut, fin, compte[0], bornes[0], bornes[1], precedente, HEX.formatHex(sha.digest()));
    }

    private static void champ(MessageDigest sha, String valeur) {
        if (valeur == null) {
            sha.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] octets = valeur.getBytes(StandardCharsets.UTF_8);
        sha.update(ByteBuffer.allocate(4).putInt(octets.length).array());
        sha.update(octets);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /* ------------------------------------------------------------------ export */

    private void exporter(Scellement s) {
        Map<String, Object> ligne = new LinkedHashMap<>();
        ligne.put("periodeDebut", s.periodeDebut().toString());
        ligne.put("periodeFin", s.periodeFin().toString());
        ligne.put("nombre", s.nombre());
        ligne.put("premierNumero", s.premierNumero());
        ligne.put("dernierNumero", s.dernierNumero());
        ligne.put("empreintePrecedente", s.empreintePrecedente());
        ligne.put("empreinte", s.empreinte());
        try {
            byte[] octets = (json.writeValueAsString(ligne) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(export.getParent());
            // Ajout seul, synchronisé sur le disque avant de valider en base.
            try (FileChannel canal = FileChannel.open(export, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                canal.write(ByteBuffer.wrap(octets));
                canal.force(true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Export du scellement impossible : " + export, e);
        }
        journalScellements.info("SCELLEMENT periode={}/{} nombre={} empreinte={} precedente={}",
                s.periodeDebut(), s.periodeFin(), s.nombre(), s.empreinte(), s.empreintePrecedente());
        journal.debug("Période {} scellée ({} enregistrements)", s.periodeDebut(), s.nombre());
    }

    /** Scellements exportés, lus depuis le fichier hors base (lignes illisibles ignorées et signalées). */
    List<Map<String, Object>> lireExport() throws IOException {
        if (!Files.exists(export)) return List.of();
        List<Map<String, Object>> lignes = new java.util.ArrayList<>();
        for (String texte : Files.readAllLines(export, StandardCharsets.UTF_8)) {
            if (texte.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> l = json.readValue(texte, Map.class);
                lignes.add(l);
            } catch (IOException e) {
                journal.warn("Ligne illisible dans l'export des scellements : {}", texte);
                lignes.add(Map.of("illisible", texte));
            }
        }
        return lignes;
    }
}
