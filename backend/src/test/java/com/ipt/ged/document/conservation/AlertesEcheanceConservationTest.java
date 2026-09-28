package com.ipt.ged.document.conservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.tache.VerrouTache;
import com.ipt.ged.identite.Role;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alerte d'échéance de conservation (T-112, DAT §12.9) : tâche quotidienne sous
 * verrou de tâche, signalement unique, notification aux seuls Agents d'archive
 * compétents, audit, filtre « échéance dépassée », aucune suppression (P4).
 *
 * <p>Sans transaction de test : la tâche travaille par tranches dans ses
 * propres transactions et ne voit que des données validées. API de recherche
 * plein texte active (sans worker OCR), comme {@code OcrApiTest} : même contexte.
 */
@SpringBootTest(properties = {"ged.ocr.chaine.actif=true", "ged.ocr.chaine.workers=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class AlertesEcheanceConservationTest {

    @Autowired private AlertesEcheanceConservation alertes;
    @Autowired private VerrouTache verrou;
    @Autowired private JeuDroits jeu;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private SearchIndexer indexeur;

    private UUID espace, autre, type;
    private UUID echu, duJour, futur, enCorbeille, archive, confidentiel;
    private UUID agent, agentAilleurs;

    @BeforeEach
    void jeu() {
        // Aucune tâche ne doit rester verrouillée d'un test à l'autre.
        jdbc.update("UPDATE verrou_tache SET verrouille_jusqu_a = now() WHERE nom = ?", AlertesEcheanceConservation.TACHE);
        espace = jeu.noeud("Conservation " + UUID.randomUUID(), null);
        autre = jeu.noeud("Autre conservation " + UUID.randomUUID(), null);
        type = jeu.type(espace, Confidentialite.PUBLIC);
        jdbc.update("UPDATE type_document SET duree_conservation_mois = 12, point_depart = 'DATE_DOCUMENT' WHERE id = ?",
                type);
        LocalDate aujourdhui = Echeances.aujourdhui();
        echu = document("Échu", aujourdhui.minusMonths(13), Confidentialite.PUBLIC);
        duJour = document("Échéance du jour", aujourdhui.minusMonths(12), Confidentialite.PUBLIC);
        futur = document("Futur", aujourdhui, Confidentialite.PUBLIC);
        enCorbeille = document("Corbeille", aujourdhui.minusMonths(20), Confidentialite.PUBLIC);
        jdbc.update("UPDATE document SET supprime = true, supprime_le = now() WHERE id = ?", enCorbeille);
        archive = document("Archivé", aujourdhui.minusMonths(15), Confidentialite.PUBLIC);
        jdbc.update("UPDATE document SET statut_conservation = 'ARCHIVE', archive_le = now() WHERE id = ?", archive);
        confidentiel = document("Confidentiel", aujourdhui.minusMonths(14), Confidentialite.CONFIDENTIEL);

        // Agent d'archive de l'espace (compétent), et agent d'un autre espace (non compétent).
        jeu.habiliter(Comptes.SECOND_ACTEUR, Role.AGENT_ARCHIVE, espace, null);
        jeu.habiliter(Comptes.TROISIEME_ACTEUR, Role.AGENT_ARCHIVE, autre, null);
        agent = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        agentAilleurs = jeu.utilisateurId(Comptes.TROISIEME_ACTEUR);
    }

    private UUID document(String nom, LocalDate dateDocument, Confidentialite niveau) {
        UUID d = jeu.document(nom + " " + UUID.randomUUID(), type, niveau, null);
        jdbc.update("UPDATE document SET date_document = ? WHERE id = ?", Date.valueOf(dateDocument), d);
        return d;
    }

    private boolean signale(UUID doc) {
        return jdbc.queryForObject("SELECT echeance_signalee_le IS NOT NULL FROM document WHERE id = ?", Boolean.class,
                doc);
    }

    private List<UUID> notifies(UUID doc) {
        return jdbc.queryForList("SELECT destinataire_id FROM notification WHERE type = 'ECHEANCE_CONSERVATION'"
                + " AND objet_id = ?", UUID.class, doc);
    }

    private int traces(UUID doc) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = ? AND objet_id = ?",
                Integer.class, ActionAudit.ECHEANCE_CONSERVATION_ATTEINTE.code(), doc);
    }

    @Test
    @DisplayName("Échéance atteinte : signalée une fois, Agent d'archive compétent notifié, audit ; rien n'est supprimé")
    void signalement() {
        assertThat(jdbc.queryForObject("SELECT echeance_conservation FROM document WHERE id = ?", LocalDate.class, echu))
                .isEqualTo(Echeances.aujourdhui().minusMonths(1));

        AlertesEcheanceConservation.Bilan bilan = alertes.executer();

        assertThat(bilan.executee()).isTrue();
        assertThat(signale(echu)).isTrue();
        assertThat(signale(duJour)).as("échéance du jour : atteinte").isTrue();
        assertThat(signale(archive)).as("un document archivé est signalé aussi").isTrue();
        assertThat(signale(futur)).isFalse();
        assertThat(signale(enCorbeille)).as("corbeille : jamais signalée").isFalse();

        // Destinataires : l'Agent d'archive de l'espace, pas celui d'un autre espace,
        // pas l'Administrateur (qui n'est pas Agent d'archive).
        assertThat(notifies(echu)).contains(agent).doesNotContain(agentAilleurs, jeu.utilisateurId(Comptes.ADMIN));
        assertThat(notifies(echu)).doesNotHaveDuplicates();
        // Confidentiel : l'agent non désigné n'en apprend pas l'existence ; la trace demeure.
        assertThat(signale(confidentiel)).isTrue();
        assertThat(notifies(confidentiel)).doesNotContain(agent, agentAilleurs);
        assertThat(traces(confidentiel)).isEqualTo(1);

        assertThat(traces(echu)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT titre FROM notification WHERE type = 'ECHEANCE_CONSERVATION'"
                + " AND objet_id = ? AND destinataire_id = ?", String.class, echu, agent)).startsWith("Fin de conservation");
        assertThat(jdbc.queryForObject("SELECT lien FROM notification WHERE objet_id = ? AND destinataire_id = ?",
                String.class, echu, agent)).isEqualTo("documents/" + echu);

        // P4 : aucune suppression ni aucun changement d'état du document.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE id IN (?, ?, ?) AND NOT supprime",
                Integer.class, echu, duJour, archive)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT statut_conservation FROM document WHERE id = ?", String.class, echu))
                .isEqualTo("ACTIF");

        // Second passage : rien de neuf pour un document déjà signalé.
        alertes.executer();
        assertThat(traces(echu)).isEqualTo(1);
        assertThat(notifies(echu)).hasSize(notifies(echu).stream().distinct().toList().size()).contains(agent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE type = 'ECHEANCE_CONSERVATION'"
                + " AND objet_id = ? AND destinataire_id = ?", Integer.class, echu, agent)).isEqualTo(1);
    }

    @Test
    @DisplayName("Verrou de tâche : pas d'exécution tant qu'une autre instance tient le verrou")
    void verrouTenuAilleurs() {
        Optional<VerrouTache.Jeton> ailleurs = verrou.prendre(AlertesEcheanceConservation.TACHE, Duration.ofHours(1));
        assertThat(ailleurs).isPresent();
        try {
            AlertesEcheanceConservation.Bilan bilan = alertes.executer();
            assertThat(bilan.executee()).isFalse();
            assertThat(signale(echu)).isFalse();
            assertThat(traces(echu)).isZero();
        } finally {
            verrou.liberer(ailleurs.get());
        }
        assertThat(alertes.executer().executee()).isTrue();
        assertThat(signale(echu)).isTrue();
    }

    @Test
    @DisplayName("Deux exécutions simultanées (deux instances) : chaque document signalé et notifié une seule fois")
    void pasDeDoubleExecution() throws Exception {
        ExecutorService fils = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch depart = new CountDownLatch(1);
            List<Future<AlertesEcheanceConservation.Bilan>> bilans = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                bilans.add(fils.submit(() -> {
                    depart.await();
                    return alertes.executer();
                }));
            }
            depart.countDown();
            for (Future<AlertesEcheanceConservation.Bilan> f : bilans) f.get(2, TimeUnit.MINUTES);
        } finally {
            fils.shutdownNow();
        }
        for (UUID doc : List.of(echu, duJour, archive, confidentiel)) {
            assertThat(traces(doc)).as("une seule trace pour " + doc).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE type = 'ECHEANCE_CONSERVATION'"
                + " AND objet_id = ? AND destinataire_id = ?", Integer.class, echu, agent)).isEqualTo(1);
    }

    @Test
    @DisplayName("Échéance repoussée dans le futur : le signalement est levé, et redonné à la nouvelle échéance")
    void echeanceRepoussee() {
        alertes.executer();
        assertThat(signale(echu)).isTrue();

        // Type prolongé à 10 ans : l'échéance recalculée par la base est future.
        jdbc.update("UPDATE type_document SET duree_conservation_mois = 120 WHERE id = ?", type);
        assertThat(signale(echu)).isFalse();

        // Nouvelle échéance atteinte (date du document corrigée) : second signalement.
        jdbc.update("UPDATE document SET date_document = ? WHERE id = ?",
                Date.valueOf(Echeances.aujourdhui().minusMonths(121)), echu);
        alertes.executer();
        assertThat(signale(echu)).isTrue();
        assertThat(traces(echu)).isEqualTo(2);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<UUID> ids(JsonNode tableau) {
        List<UUID> l = new ArrayList<>();
        tableau.forEach(n -> l.add(UUID.fromString(n.get("id").asText())));
        return l;
    }

    private static List<UUID> documents(JsonNode resultats) {
        List<UUID> l = new ArrayList<>();
        resultats.forEach(n -> l.add(UUID.fromString(n.get("documentId").asText())));
        return l;
    }

    @Test
    @DisplayName("Filtre « échéance dépassée » : liste des documents, recherche par métadonnées, recherche plein texte")
    void filtreEcheanceDepassee() throws Exception {
        // Liste des documents de l'espace, échus seulement, avec la mise en évidence.
        JsonNode liste = json(mvc.perform(get("/api/v1/documents").param("workspaceId", espace.toString())
                .param("echeanceDepassee", "true").param("size", "200")));
        assertThat(ids(liste.get("content"))).contains(echu, duJour, archive).doesNotContain(futur, enCorbeille);
        liste.get("content").forEach(n -> assertThat(n.get("echeanceDepassee").asBoolean()).isTrue());
        JsonNode tous = json(mvc.perform(get("/api/v1/documents").param("workspaceId", espace.toString())
                .param("size", "200")));
        assertThat(ids(tous.get("content"))).contains(futur);
        tous.get("content").forEach(n -> {
            if (n.get("id").asText().equals(futur.toString())) assertThat(n.get("echeanceDepassee").asBoolean()).isFalse();
        });

        // Recherche par métadonnées (§12.7).
        JsonNode meta = json(mvc.perform(post("/api/v1/documents/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"echeanceDepassee\":true}")));
        assertThat(ids(meta.get("content"))).contains(echu, duJour).doesNotContain(futur);

        // Recherche plein texte (écran de recherche) : même filtre.
        String mot = "conservatio" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        for (UUID doc : List.of(echu, futur)) {
            UUID version = UUID.randomUUID();
            jdbc.update("INSERT INTO version_document (id, document_id, file_name, numero, courante)"
                    + " VALUES (?, ?, 'f.pdf', 1, true)", version, doc);
            indexeur.indexer(new SearchIndexer.TexteAIndexer(doc, version, "fra", "Registre " + mot, "OCR", 1));
        }
        JsonNode texte = json(mvc.perform(get("/api/v1/recherche/plein-texte").param("q", mot)));
        assertThat(documents(texte.get("resultats"))).containsExactlyInAnyOrder(echu, futur);
        JsonNode filtre = json(mvc.perform(get("/api/v1/recherche/plein-texte").param("q", mot)
                .param("echeanceDepassee", "true")));
        assertThat(documents(filtre.get("resultats"))).containsExactly(echu);
    }
}
