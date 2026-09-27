package com.ipt.ged.idempotence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotence des créations (DAT §5.3.2), de bout en bout sur PostgreSQL :
 * clé obligatoire, rejeu sans doublon (JSON et multipart), conflit de contenu,
 * rejeu autorisé après un refus, purge des entrées expirées.
 *
 * <p>Sans transaction de test : la réservation de la clé est validée aussitôt,
 * comme en production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class IdempotenceApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DepotIdempotence depot;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlatformTransactionManager transactions;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entites;

    private UUID admin;
    private UUID workflow;

    @BeforeEach
    void preparer() {
        admin = Comptes.idAdmin(employeRepository);
        Employe e = employeRepository.findById(admin).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF idempotence " + UUID.randomUUID());
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflow = workflowRepository.save(wf).getId();
    }

    private String espace(String code, String nom) {
        return "{\"name\":\"" + nom + "\",\"code\":\"" + code + "\",\"employeId\":\"" + admin
                + "\",\"workflowId\":\"" + workflow + "\"}";
    }

    private long espacesDeCode(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM workspace WHERE code = ?", Long.class, code);
    }

    @Test
    @DisplayName("Création sans Idempotency-Key : 400 IDEMPOTENCE_CLE_ABSENTE ; clé non UUID : 400")
    void cleObligatoire() throws Exception {
        String code = "WS-IDP-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON).content(espace(code, "Sans clé"))
                        .header(FiltreIdempotence.ENTETE, CleIdempotenceParDefautDesTests.SANS_CLE))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(CodesErreurIdempotence.IDEMPOTENCE_CLE_ABSENTE));
        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON).content(espace(code, "Clé invalide"))
                        .header(FiltreIdempotence.ENTETE, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CodesErreurIdempotence.IDEMPOTENCE_CLE_INVALIDE));
        assertThat(espacesDeCode(code)).isZero();
        // Une lecture n'est pas concernée.
        mvc.perform(get("/api/v1/workspaces").header(FiltreIdempotence.ENTETE, CleIdempotenceParDefautDesTests.SANS_CLE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Rejeu identique : réponse initiale renvoyée, aucun doublon ; contenu différent : 422")
    void rejeuEtConflit() throws Exception {
        String code = "WS-IDP-" + UUID.randomUUID().toString().substring(0, 8);
        String cle = UUID.randomUUID().toString();

        MvcResult premier = mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content(espace(code, "Marchés")).header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content(espace(code, "Marchés")).header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isCreated())
                .andExpect(header().string(FiltreIdempotence.ENTETE_REJEU, "true"))
                .andReturn();

        assertThat(om.readTree(second.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asText())
                .isEqualTo(om.readTree(premier.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asText());
        assertThat(espacesDeCode(code)).isEqualTo(1);

        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content(espace(code, "Autre nom")).header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurIdempotence.IDEMPOTENCE_CONFLIT));
        assertThat(espacesDeCode(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("Après un refus, la même clé peut resservir avec une requête corrigée")
    void refusNonMemorise() throws Exception {
        String cle = UUID.randomUUID().toString();
        String code = "WS-IDP-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"" + code + "\"}").header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content(espace(code, "Corrigé")).header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isCreated());
        assertThat(espacesDeCode(code)).isEqualTo(1);
    }

    @Test
    @DisplayName("Dépôt multipart rejoué avec la même clé : un seul document")
    void depotMultipart() {
        // Dans une transaction annulée à la fin : le document et son circuit de
        // validation ne doivent pas rester visibles des autres suites de tests.
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(statut -> {
            try {
                depotMultipartDansLaTransaction();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                statut.setRollbackOnly();
            }
        });
    }

    private void depotMultipartDansLaTransaction() throws Exception {
        Employe e = employeRepository.findById(admin).orElseThrow();
        WorkSpace w = new WorkSpace("Idempotence dépôt", "WS-IDD-" + UUID.randomUUID().toString().substring(0, 8));
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(workflowRepository.findById(workflow).orElseThrow());
        workspaceRepository.save(w);
        TypeDocument type = new TypeDocument("TD-IDD-" + UUID.randomUUID().toString().substring(0, 8), "Courrier");
        type.setDescription("Courriers entrants");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        UUID typeId = typeRepository.save(type).getId();

        String nom = "Courrier " + UUID.randomUUID();
        String cle = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart("/api/v1/documents")
                            .file(new MockMultipartFile("file", "courrier.pdf", "application/pdf",
                                    "%PDF-1.4 contenu".getBytes()))
                            .param("name", nom).param("typeDocumentId", typeId.toString())
                            .header(FiltreIdempotence.ENTETE, cle))
                    .andExpect(status().isCreated());
        }
        entites.flush();   // écritures JPA de la transaction visibles de la requête SQL
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE name = ?", Long.class, nom)).isEqualTo(1);

        // Même clé, autre fichier : conflit.
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "courrier.pdf", "application/pdf", "%PDF-1.4 autre".getBytes()))
                        .param("name", nom).param("typeDocumentId", typeId.toString())
                        .header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurIdempotence.IDEMPOTENCE_CONFLIT));
    }

    @Test
    @DisplayName("Portée par appelant : la même clé est indépendante pour une application et pour un utilisateur")
    void porteeParAppelant() throws Exception {
        UUID appId = UUID.fromString(om.readTree(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"app-idp-" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"nom\":\"Essai idempotence\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asText());
        String cleApi = om.readTree(mvc.perform(post("/api/v1/applications/" + appId + "/cles")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("cle").asText();

        String cle = UUID.randomUUID().toString();
        String codeApp = "WS-IDA-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/workspaces").with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.anonymous())
                            .header("X-API-Key", cleApi).contentType(APPLICATION_JSON)
                            .content(espace(codeApp, "Par l'application")).header(FiltreIdempotence.ENTETE, cle))
                    .andExpect(status().isCreated());
        }
        assertThat(espacesDeCode(codeApp)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT application_id FROM idempotence_cle WHERE cle = ?", UUID.class,
                UUID.fromString(cle))).isEqualTo(appId);

        // Le même UUID, envoyé par un utilisateur, est une autre clé : pas de rejeu ni de conflit.
        String codeUtilisateur = "WS-IDU-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                        .content(espace(codeUtilisateur, "Par l'utilisateur")).header(FiltreIdempotence.ENTETE, cle))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(FiltreIdempotence.ENTETE_REJEU));
        assertThat(espacesDeCode(codeUtilisateur)).isEqualTo(1);
    }

    @Test
    @DisplayName("Purge : les clés expirées sont supprimées, les autres conservées")
    void purge() {
        Instant maintenant = Instant.now();
        UUID expiree = depot.reserver(null, "utilisateur:essai-purge", UUID.randomUUID(), "POST", "/x", "0".repeat(64),
                maintenant.minus(2, ChronoUnit.DAYS), maintenant.minus(1, ChronoUnit.DAYS)).orElseThrow();
        UUID valide = depot.reserver(null, "utilisateur:essai-purge", UUID.randomUUID(), "POST", "/x", "0".repeat(64),
                maintenant, maintenant.plus(1, ChronoUnit.DAYS)).orElseThrow();
        assertThat(depot.purger(maintenant)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotence_cle WHERE id = ?", Long.class, expiree)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotence_cle WHERE id = ?", Long.class, valide)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expire_le FROM idempotence_cle WHERE id = ?", Timestamp.class, valide)
                .toInstant()).isAfter(maintenant);
    }
}
