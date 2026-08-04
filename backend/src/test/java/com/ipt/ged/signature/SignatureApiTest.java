package com.ipt.ged.signature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Campagne de tests du circuit de signature : création à l'upload, approbation
 * séquentielle, rejet (retour en arrière), et contrôle de l'assigné.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SignatureApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private long typeId;

    @BeforeEach
    void setup() {
        Employe e1 = employeRepository.findById(1L).orElseThrow();
        Employe e2 = employeRepository.findById(2L).orElseThrow();

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

    private long upload(String name) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", name + ".pdf", "application/pdf", "x".getBytes()))
                        .param("name", name)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(res).get("id").asLong();
    }

    private long sigIdForStep(long docId, int stepOrder) throws Exception {
        String res = mvc.perform(get("/api/v1/signatures/document/" + docId))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode n : om.readTree(res)) {
            if (n.get("stepOrder").asInt() == stepOrder) return n.get("id").asLong();
        }
        throw new IllegalStateException("Étape " + stepOrder + " introuvable");
    }

    private boolean docActive(long docId) throws Exception {
        String res = mvc.perform(get("/api/v1/documents/" + docId))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(res).get("active").asBoolean();
    }

    @Test
    @DisplayName("1. L'upload crée le circuit : document en attente, étape 1 actionnable")
    void uploadCreatesCircuit() throws Exception {
        long doc = upload("Doc A");
        // document en attente (non actif)
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
        // employé 1 (étape 1) : 1 signature actionnable ; employé 2 (étape 2) : 0
        mvc.perform(get("/api/v1/signatures/pending").param("employeId", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stepLabel").value("Contrôle"));
        mvc.perform(get("/api/v1/signatures/pending").param("employeId", "2"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("2. Approbation séquentielle : la dernière étape active le document")
    void sequentialApproval() throws Exception {
        long doc = upload("Doc B");
        long s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
        // pas encore actif (étape 2 en attente)
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
        // maintenant l'étape 2 est actionnable
        mvc.perform(get("/api/v1/signatures/pending").param("employeId", "2"))
                .andExpect(jsonPath("$.length()").value(1));

        long s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentActive").value(true));
        org.junit.jupiter.api.Assertions.assertTrue(docActive(doc));
    }

    @Test
    @DisplayName("3. Rejet à l'étape 2 : rouvre l'étape 1, document non actif")
    void rejectReopensPrevious() throws Exception {
        long doc = upload("Doc C");
        long s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                .content("{\"employeId\":1}")).andExpect(status().isOk());

        long s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/reject").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":2,\"motif\":\"Montant erroné\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // l'étape 1 est de nouveau en attente pour l'employé 1
        mvc.perform(get("/api/v1/signatures/pending").param("employeId", "1"))
                .andExpect(jsonPath("$.length()").value(1));
        org.junit.jupiter.api.Assertions.assertFalse(docActive(doc));
    }

    @Test
    @DisplayName("4. Seul l'assigné peut signer (400)")
    void wrongAssignee() throws Exception {
        long doc = upload("Doc D");
        long s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/approve").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":2}")) // ce n'est pas l'assigné (étape 1 = employé 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));
    }

    @Test
    @DisplayName("5. Rejet sans motif refusé (400)")
    void rejectWithoutMotif() throws Exception {
        long doc = upload("Doc E");
        long s1 = sigIdForStep(doc, 1);
        mvc.perform(patch("/api/v1/signatures/" + s1 + "/reject").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("motif")));
    }

    @Test
    @DisplayName("6. Impossible d'approuver l'étape 2 avant l'étape 1 (400)")
    void cannotSkipStep() throws Exception {
        long doc = upload("Doc F");
        long s2 = sigIdForStep(doc, 2);
        mvc.perform(patch("/api/v1/signatures/" + s2 + "/approve").contentType(APPLICATION_JSON)
                        .content("{\"employeId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("précédente")));
    }
}
