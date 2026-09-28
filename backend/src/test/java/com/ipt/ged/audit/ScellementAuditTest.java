package com.ipt.ged.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scellement horaire chaîné et vérification (DAT §7.4.2) sur PostgreSQL réel.
 *
 * <p>Le cas décisif : une altération directe en base par le propriétaire des
 * tables ({@code ged_owner}, qui peut désactiver le déclencheur) est détectée
 * par la vérification — y compris quand le scellement en base est lui aussi
 * réécrit, grâce à la copie hors base.
 *
 * <p>Les enregistrements d'essai sont datés d'heures passées (l'application ne
 * scelle que des périodes closes) ; les altérations sont annulées en fin de
 * test pour laisser la chaîne intègre aux tests suivants.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScellementAuditTest {

    @Autowired private ScellementAudit scellement;
    @Autowired private VerificationAudit verification;
    @Autowired private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.liquibase.user}") private String proprietaire;
    @Value("${spring.liquibase.password:}") private String motDePasseProprietaire;
    @Value("${ged.audit.scellement.fichier-export}") private String export;

    private static final UUID OBJET = UUID.randomUUID();

    /** La base est recréée à chaque exécution de la suite : l'export de l'exécution précédente ne la concerne pas. */
    @BeforeAll
    static void exportVierge() throws IOException {
        Files.deleteIfExists(Path.of("./target/audit/scellements.jsonl"));
    }

    private void inscrireDansLePasse(int heures, String motif) {
        Instant quand = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(heures, ChronoUnit.HOURS).plusSeconds(90);
        // ged_app peut insérer (et seulement insérer) : l'horodatage explicite
        // simule une action ancienne.
        jdbc.update("""
                INSERT INTO journal_audit (horodatage, action, objet_type, objet_id, resultat, motif, apres)
                VALUES (?, 'ESPACE_MODIFIE', 'ESSAI', ?, 'SUCCES', ?, '{"nom": "Contrats"}'::jsonb)""",
                Timestamp.from(quand), OBJET, motif);
    }

    @Test
    @Order(1)
    @DisplayName("Les périodes closes sont scellées en chaîne, en base et hors base")
    void scellementChaine() throws IOException {
        inscrireDansLePasse(3, "premier");
        inscrireDansLePasse(3, "deuxième");
        inscrireDansLePasse(2, "troisième");

        List<ScellementAudit.Scellement> produits = scellement.scellerPeriodesCloses();

        assertThat(produits).isNotEmpty();
        for (int i = 1; i < produits.size(); i++) {
            assertThat(produits.get(i).empreintePrecedente()).isEqualTo(produits.get(i - 1).empreinte());
            assertThat(produits.get(i).periodeDebut()).isEqualTo(produits.get(i - 1).periodeFin());
        }
        assertThat(produits.stream().mapToLong(ScellementAudit.Scellement::nombre).sum()).isGreaterThanOrEqualTo(3);
        // Aucune période ouverte n'est scellée.
        assertThat(produits.get(produits.size() - 1).periodeFin()).isBeforeOrEqualTo(Instant.now());

        String copie = Files.readString(Path.of(export), StandardCharsets.UTF_8);
        for (ScellementAudit.Scellement s : produits) {
            assertThat(copie).contains(s.empreinte());
        }
        // Rejouer ne rescelle rien.
        assertThat(scellement.scellerPeriodesCloses()).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("Chaîne intègre : la vérification ne relève aucune anomalie, et elle est tracée")
    void verificationIntegre() {
        VerificationAudit.Rapport rapport = verification.verifier();
        assertThat(rapport.anomalies()).isEmpty();
        assertThat(rapport.periodesVerifiees()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT resultat FROM journal_audit WHERE action = 'AUDIT_VERIFIE' ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("SUCCES");
    }

    @Test
    @Order(3)
    @DisplayName("Altération directe par ged_owner (déclencheur désactivé) : la vérification échoue")
    void alterationDetectee() throws SQLException {
        Map<String, Object> cible = jdbc.queryForMap(
                "SELECT id, horodatage, tableoid::regclass::text AS partition FROM journal_audit"
                        + " WHERE objet_id = ? AND motif = 'premier'", OBJET);
        String partition = (String) cible.get("partition");
        String modifier = "UPDATE " + partition + " SET motif = %s WHERE id = " + cible.get("id");
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            contourner(c, partition, String.format(modifier, "'falsifié'"));

            VerificationAudit.Rapport rapport = verification.verifier();

            assertThat(rapport.integre()).isFalse();
            assertThat(rapport.anomalies()).extracting(VerificationAudit.Anomalie::type)
                    .contains("EMPREINTE_DIFFERENTE");
            assertThat(jdbc.queryForObject(
                    "SELECT resultat FROM journal_audit WHERE action = 'AUDIT_VERIFIE' ORDER BY id DESC LIMIT 1",
                    String.class)).isEqualTo("ECHEC");

            // Remise en état pour les tests suivants.
            contourner(c, partition, String.format(modifier, "'premier'"));
        }
        assertThat(verification.verifier().anomalies()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("Scellement réécrit en base pour masquer une altération : la copie hors base le trahit")
    void scellementReecritDetecte() throws SQLException {
        Map<String, Object> cible = jdbc.queryForMap(
                "SELECT id, tableoid::regclass::text AS partition FROM journal_audit"
                        + " WHERE objet_id = ? AND motif = 'troisième'", OBJET);
        String partition = (String) cible.get("partition");
        Map<String, Object> scelle = jdbc.queryForMap("""
                SELECT id, periode_debut, periode_fin, empreinte_precedente, empreinte
                  FROM journal_audit_scellement
                 WHERE periode_debut <= (SELECT horodatage FROM journal_audit WHERE id = ? AND objet_id = ?)
                 ORDER BY periode_debut DESC LIMIT 1""", cible.get("id"), OBJET);
        String empreinteOrigine = ((String) scelle.get("empreinte")).trim();
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            // Le fraudeur modifie la ligne ET recalcule le scellement de sa période.
            contourner(c, partition, "UPDATE " + partition + " SET motif = 'falsifié' WHERE id = " + cible.get("id"));
            String fausse = scellement.calculer(((Timestamp) scelle.get("periode_debut")).toInstant(),
                    ((Timestamp) scelle.get("periode_fin")).toInstant(),
                    ((String) scelle.get("empreinte_precedente")).trim()).empreinte();
            contourner(c, "journal_audit_scellement",
                    "UPDATE journal_audit_scellement SET empreinte = '" + fausse + "' WHERE id = '" + scelle.get("id") + "'");

            List<String> types = verification.verifier().anomalies().stream()
                    .map(VerificationAudit.Anomalie::type).toList();
            assertThat(types).contains("EXPORT_DIFFERENT");

            contourner(c, partition, "UPDATE " + partition + " SET motif = 'troisième' WHERE id = " + cible.get("id"));
            contourner(c, "journal_audit_scellement", "UPDATE journal_audit_scellement SET empreinte = '"
                    + empreinteOrigine + "' WHERE id = '" + scelle.get("id") + "'");
        }
        assertThat(verification.verifier().anomalies()).isEmpty();
    }

    @Test
    @Order(10)
    @DisplayName("Bornes numériques d'une période de plus de 100 lignes (ANO-E4-004) ; chaîne inchangée")
    void bornesNumeriques() {
        // Période à venir (dans trois heures : aucune autre ligne pendant la suite) : 150 lignes.
        // Les numéros sont posés explicitement pour franchir à coup sûr des changements
        // de nombre de chiffres (-1 à -150 : « -99 » > « -150 » en ordre textuel, comme
        // « 99 » > « 274 » dans l'anomalie), quel que soit l'état de la séquence ; négatifs,
        // ils ne croisent ni la séquence ni les tests qui lisent le dernier événement.
        Instant debut = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(3, ChronoUnit.HOURS);
        Instant fin = debut.plus(1, ChronoUnit.HOURS);
        for (int i = 1; i <= 150; i++) {
            jdbc.update("""
                    INSERT INTO journal_audit (id, horodatage, action, objet_type, objet_id, resultat, motif)
                    VALUES (?, ?, 'ESPACE_MODIFIE', 'ESSAI', ?, 'SUCCES', 'bornes')""",
                    -i, Timestamp.from(debut.plusMillis(i)), OBJET);
        }
        ScellementAudit.Scellement s = scellement.calculer(debut, fin, "0".repeat(64));
        assertThat(s.nombre()).isEqualTo(150);
        assertThat(s.premierNumero()).isEqualTo(-150L);
        assertThat(s.dernierNumero()).isEqualTo(-1L);
        // Bornes réparées sans toucher à l'ordre canonique de la chaîne (scellements existants vérifiables).
        assertThat(ScellementAudit.LIGNES_PERIODE).contains("ORDER BY 1");
    }

    /** Ce que peut faire le propriétaire des tables : désactiver le déclencheur le temps d'une requête. */
    private static void contourner(Connection c, String table, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
            try {
                s.execute(sql);
            } finally {
                s.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
        }
    }
}
