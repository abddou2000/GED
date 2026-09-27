package com.ipt.ged.signature;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

/**
 * Campagne de tests du circuit de signature : création à l'upload, approbation
 * séquentielle, rejet (retour en arrière), et contrôle de l'assigné.
 *
 * <h2>Ce qui a changé avec le correctif « l'identité vient du jeton »</h2>
 * <p>Ces tests envoyaient auparavant l'acteur au serveur — {@code ?employeId=2}
 * sur les listes, {@code {"employeId":2}} dans le corps des approbations. C'est
 * précisément la faille corrigée : le serveur demandait à l'appelant qui il
 * était. Les appels ci-dessous n'envoient plus rien de tel ; l'acteur est
 * <b>incarné</b>, soit par l'annotation de classe (le super-admin), soit par le
 * post-processeur {@link #enTantQue(String)} pour un acteur différent au sein
 * d'un même test.
 *
 * <p>L'identité de classe est l'Administrateur ({@code Comptes.ADMIN}) ; le
 * second acteur — l'assigné de l'étape 2 — est une autre identité de l'annuaire
 * simulé ({@code Comptes.SECOND_ACTEUR}), provisionnée à la demande.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class SignatureApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private UserDetailsService utilisateurs;

    private UUID typeId;

    /**
     * Incarne un autre compte le temps d'une requête.
     *
     * <p>On passe par le vrai {@code ServiceUtilisateurs} : le principal est un
     * {@code UtilisateurConnecte} authentique, avec l'{@code employeId} lu en
     * base. Un {@code user("x")} nu produirait un principal d'un autre type
     * et {@code @AuthenticationPrincipal UtilisateurConnecte} recevrait
     * {@code null} — le test passerait à côté de ce qu'il prétend vérifier.
     */
    private RequestPostProcessor enTantQue(String identifiant) {
        return user(utilisateurs.loadUserByUsername(identifiant));
    }

    @BeforeEach
    void setup() {
        Employe e1 = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        Employe e2 = employeRepository.findById(Comptes.idSecondActeur(employeRepository)).orElseThrow();

        WorkflowGed wf = new WorkflowGed("Circuit 2 étapes");
        wf.addStep(new WorkflowStep(e1, "Contrôle", 1));
        wf.addStep(new WorkflowStep(e2, "Validation", 2));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-SIG");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e1);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-SIG", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private UUID upload(String name) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", name + ".pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("name", name)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    private UUID sigIdForStep(UUID docId, int stepOrder) throws Exception {
        String res = mvc.perform(get("/api/v1/signatures/document/" + docId))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode n : om.readTree(res)) {
            if (n.get("stepOrder").asInt() == stepOrder) return UUID.fromString(n.get("id").asText());
        }
        throw new IllegalStateException("Étape " + stepOrder + " introuvable");
    }

    private boolean docActive(UUID docId) throws Exception {
        String res = mvc.perform(get("/api/v1/documents/" + docId))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(res).get("active").asBoolean();
    }

    @Test
    @DisplayName("1. L'upload crée le circuit : document en attente, étape 1 actionnable")
    void uploadCreatesCircuit() throws Exception {
        UUID doc = upload("Doc A");
        // document en attente (non actif)
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
        // /pending ne prend plus aucun paramètre : c'est le porteur du jeton qui
        // détermine la liste. Employé 1 (étape 1) : 1 actionnable.
        mvc.perform(get("/api/v1/signatures/pending"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stepLabel").value("Contrôle"));
        // Employé 2 (étape 2) : rien tant que l'étape 1 n'est pas signée.
        mvc.perform(get("/api/v1/signatures/pending").with(enTantQue(Comptes.SECOND_ACTEUR)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("2. Approbation séquentielle : la dernière étape active le document")
    void sequentialApproval() throws Exception {
        UUID doc = upload("Doc B");
        UUID s1 = sigIdForStep(doc, 1);
        // Employé 1 signe la sienne — aucun employeId dans le corps.
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
        // pas encore actif (étape 2 en attente)
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
        // maintenant l'étape 2 est actionnable, pour l'employé 2 et lui seul
        mvc.perform(get("/api/v1/signatures/pending").with(enTantQue(Comptes.SECOND_ACTEUR)))
                .andExpect(jsonPath("$.length()").value(1));

        UUID s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentActive").value(true));
        org.junit.jupiter.api.Assertions.assertTrue(docActive(doc));
    }

    @Test
    @DisplayName("3. Rejet à l'étape 2 : rouvre l'étape 1, document non actif")
    void rejectReopensPrevious() throws Exception {
        UUID doc = upload("Doc C");
        UUID s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());

        UUID s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/reject")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{\"motif\":\"Montant erroné\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // l'étape 1 est de nouveau en attente pour l'employé 1
        mvc.perform(get("/api/v1/signatures/pending"))
                .andExpect(jsonPath("$.length()").value(1));
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
    }

    @Test
    @DisplayName("3b. Un circuit rejeté se relance : l'étape refusée redevient à traiter")
    void rejetRelancable() throws Exception {
        UUID doc = upload("Doc C2");
        UUID s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());

        UUID s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/reject")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{\"motif\":\"Montant erroné\"}"))
                .andExpect(status().isOk());

        // Avant la relance, l'étape refusée est un cul-de-sac : on ne peut pas
        // l'approuver. C'est exactement ce qui condamnait le document.
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/signatures/document/" + doc + "/relancer"))
                .andExpect(status().isOk());

        // L'étape refusée est de nouveau à traiter, son MOTIF est conservé —
        // l'approbateur doit savoir ce qui avait été reproché.
        mvc.perform(get("/api/v1/signatures/document/" + doc))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].motif").value("Montant erroné"));

        /* Le circuit repart DANS L'ORDRE : le rejet avait rouvert l'étape 1,
           elle se re-signe d'abord. C'est voulu — le refus fait revenir la
           pièce en arrière, pas seulement chez celui qui l'a refusée. */
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("3c. Relancer un circuit sans rejet est refusé (400)")
    void relanceSansRejetRefusee() throws Exception {
        UUID doc = upload("Doc C3");
        mvc.perform(patch("/api/v1/signatures/document/" + doc + "/relancer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4. Seul l'assigné peut signer : un autre employé authentifié est refusé (400)")
    void wrongAssignee() throws Exception {
        UUID doc = upload("Doc D");
        UUID s1 = sigIdForStep(doc, 1); // assignée à l'employé 1
        // L'employé 2 tente de signer avec SON PROPRE jeton. Rien dans la requête
        // ne lui permet plus de se déclarer employé 1.
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));
    }

    @Test
    @DisplayName("5. Rejet sans motif refusé (400)")
    void rejectWithoutMotif() throws Exception {
        UUID doc = upload("Doc E");
        UUID s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/reject").contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("motif")));
    }

    @Test
    @DisplayName("6. Impossible d'approuver l'étape 2 avant l'étape 1 (400)")
    void cannotSkipStep() throws Exception {
        UUID doc = upload("Doc F");
        UUID s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve")
                        .with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("précédente")));
    }
}
