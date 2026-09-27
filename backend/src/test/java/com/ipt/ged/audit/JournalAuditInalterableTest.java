package com.ipt.ged.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garanties de non-modification du journal (DAT §7.4.2, revue D11), éprouvées
 * sur PostgreSQL réel :
 * <ul>
 *   <li>connecté en {@code ged_app} (le compte de l'application) : INSERT et
 *       SELECT seuls, UPDATE, DELETE et TRUNCATE refusés ;</li>
 *   <li>connecté en {@code ged_owner} (propriétaire des tables) : le
 *       déclencheur refuse encore UPDATE, DELETE et TRUNCATE, sur la table mère
 *       comme sur une partition ;</li>
 *   <li>un succès suit sa transaction, un refus y survit.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class JournalAuditInalterableTest {

    @Autowired private AuditService audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationEventPublisher evenements;

    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.username}") private String compteApplication;
    @Value("${spring.liquibase.user}") private String proprietaire;
    @Value("${spring.liquibase.password:}") private String motDePasseProprietaire;

    @AfterEach
    void nettoyer() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private UUID inscrire(ActionAudit action) {
        UUID objet = UUID.randomUUID();
        audit.enregistrer(EntreeAudit.de(action, "ESSAI", objet).avecApres(Map.of("nom", "essai")));
        return objet;
    }

    private Map<String, Object> ligne(UUID objet) {
        List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT * FROM journal_audit WHERE objet_id = ? ORDER BY id", objet);
        return l.isEmpty() ? null : l.get(0);
    }

    @Test
    @DisplayName("Inscription : acteur, IP et traceId pris dans le contexte de la requête")
    void inscriptionAvecContexte() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("a.benali", null, List.of()));
        MDC.put("ip", "41.250.12.3");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

        UUID objet = inscrire(ActionAudit.ESPACE_CREE);

        Map<String, Object> l = ligne(objet);
        assertThat(l).isNotNull();
        assertThat(l.get("action")).isEqualTo("ESPACE_CREE");
        assertThat(l.get("resultat")).isEqualTo("SUCCES");
        assertThat(l.get("acteur_nom")).isEqualTo("a.benali");
        assertThat(l.get("adresse_ip")).isEqualTo("41.250.12.3");
        assertThat(l.get("trace_id")).isEqualTo(UUID.fromString("4bf92f35-77b3-4da6-a3ce-929d0e0e4736"));
        assertThat(l.get("apres").toString()).contains("\"nom\": \"essai\"");
        assertThat(l.get("id")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("Événement publié par un autre lot : inscrit par l'écouteur")
    void evenementPublie() {
        UUID objet = UUID.randomUUID();
        evenements.publishEvent(new EvenementAudit() {
            public String action() { return "DOCUMENT_TELECHARGE"; }
            public String objetType() { return "DOCUMENT"; }
            public UUID objetId() { return objet; }
        });
        assertThat(ligne(objet)).containsEntry("action", "DOCUMENT_TELECHARGE");
    }

    @Test
    @DisplayName("Un succès suit le sort de sa transaction ; un refus survit à son annulation")
    void transactions() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID succes = UUID.randomUUID();
        UUID refus = UUID.randomUUID();
        tx.executeWithoutResult(statut -> {
            audit.enregistrer(EntreeAudit.de(ActionAudit.ESPACE_SUPPRIME, "ESPACE", succes));
            audit.enregistrer(EntreeAudit.de(ActionAudit.ACCES_REFUSE, "ESPACE", refus).refus("droit Supprimer absent"));
            statut.setRollbackOnly();
        });
        assertThat(ligne(succes)).as("succès annulé avec l'action").isNull();
        assertThat(ligne(refus)).as("refus conservé").containsEntry("resultat", "REFUS")
                .containsEntry("motif", "droit Supprimer absent");
    }

    @Test
    @DisplayName("Connecté en ged_app : UPDATE, DELETE et TRUNCATE refusés (privilèges)")
    void compteApplicationSansModification() {
        assertThat(compteApplication).isEqualTo("ged_app");
        UUID objet = inscrire(ActionAudit.ESPACE_MODIFIE);
        String partition = partitionCourante();

        for (String sql : List.of(
                "UPDATE journal_audit SET motif = 'falsifié' WHERE objet_id = '" + objet + "'",
                "DELETE FROM journal_audit WHERE objet_id = '" + objet + "'",
                "TRUNCATE journal_audit",
                "UPDATE " + partition + " SET motif = 'falsifié' WHERE objet_id = '" + objet + "'",
                "DELETE FROM " + partition + " WHERE objet_id = '" + objet + "'",
                "TRUNCATE " + partition,
                "UPDATE journal_audit_scellement SET nombre = 0",
                "DELETE FROM journal_audit_scellement",
                "TRUNCATE journal_audit_scellement")) {
            // 42501 insufficient_privilege : le privilège manque, avant même le déclencheur.
            assertThatThrownBy(() -> jdbc.execute(sql)).as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
        assertThat(ligne(objet)).containsEntry("motif", null);
    }

    @Test
    @DisplayName("Connecté en ged_owner : le déclencheur refuse UPDATE, DELETE et TRUNCATE")
    void declencheursPourLeProprietaire() throws SQLException {
        UUID objet = inscrire(ActionAudit.ESPACE_ARCHIVE);
        String partition = partitionCourante();
        try (Connection c = DriverManager.getConnection(url, proprietaire, motDePasseProprietaire)) {
            for (String sql : List.of(
                    "UPDATE journal_audit SET motif = 'falsifié' WHERE objet_id = '" + objet + "'",
                    "DELETE FROM journal_audit WHERE objet_id = '" + objet + "'",
                    "TRUNCATE journal_audit",
                    "UPDATE " + partition + " SET motif = 'falsifié'",
                    "DELETE FROM " + partition,
                    "TRUNCATE " + partition,
                    "UPDATE journal_audit_scellement SET nombre = 0",
                    "DELETE FROM journal_audit_scellement",
                    "TRUNCATE journal_audit_scellement")) {
                assertThatThrownBy(() -> executer(c, sql)).as(sql)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ajout seul");
            }
        }
        assertThat(ligne(objet)).isNotNull().containsEntry("motif", null);
    }

    private String partitionCourante() {
        return jdbc.queryForObject("SELECT tableoid::regclass::text FROM journal_audit ORDER BY id DESC LIMIT 1",
                String.class);
    }

    private static void executer(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
