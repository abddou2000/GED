package com.ipt.ged.typedocument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Campagne de tests du module « Type de document » : CRUD, unicité, relations
 * (dossier obligatoire, plan facultatif), formats et taille minimale, corbeille.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class TypeDocumentApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private PlanIndexationRepository planRepository;

    private static final String BASE = "/api/v1/type-documents";
    private long workspaceId;
    private long planId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(1L).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF test");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-TD");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();

        planId = planRepository.save(new PlanIndexation("PL-TD", "Fiche")).getId();
    }

    private String body(String code, String type, Long wsId, Long planId, String typesJson, int taille) {
        return "{\"code\":\"" + code + "\",\"typeDeDocument\":\"" + type + "\",\"description\":\"desc\","
                + "\"workspaceId\":" + wsId + (planId != null ? ",\"planIndexationId\":" + planId : "")
                + ",\"typeAutorise\":" + typesJson + ",\"tailleMaxMo\":" + taille + "}";
    }

    private long create(String code, String type, String typesJson, int taille) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body(code, type, workspaceId, planId, typesJson, taille)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(res).get("id").asLong();
    }

    @Test
    @DisplayName("1. Création avec dossier, plan et formats (201)")
    void createType() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("TD-1", "Facture", workspaceId, planId, "[\"pdf\",\"docx\"]", 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeDeDocument").value("Facture"))
                .andExpect(jsonPath("$.workspace.id").value((int) workspaceId))
                .andExpect(jsonPath("$.planIndexation.label").value("Fiche"))
                .andExpect(jsonPath("$.typeAutorise[0]").value("pdf"))
                .andExpect(jsonPath("$.typeAutorise[1]").value("docx"))
                .andExpect(jsonPath("$.tailleMaxMo").value(10));
    }

    @Test
    @DisplayName("2. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("TD-DUP", "Un", "[\"pdf\"]", 10);
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("TD-DUP", "Deux", workspaceId, planId, "[\"pdf\"]", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("3. Dossier manquant refusé (400)")
    void missingWorkspace() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TD-X\",\"typeDeDocument\":\"X\",\"description\":\"d\",\"typeAutorise\":[\"pdf\"],\"tailleMaxMo\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.workspaceId").exists());
    }

    @Test
    @DisplayName("4. Taille sous le minimum refusée (400)")
    void tailleTooSmall() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("TD-SM", "Petit", workspaceId, planId, "[\"pdf\"]", 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tailleMaxMo").exists());
    }

    @Test
    @DisplayName("5. Aucun format refusé (400)")
    void emptyFormats() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("TD-NF", "SansFormat", workspaceId, planId, "[]", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.typeAutorise").exists());
    }

    @Test
    @DisplayName("6. Plan facultatif : création sans plan (201)")
    void createWithoutPlan() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("TD-NOPLAN", "Libre", workspaceId, null, "[\"pdf\"]", 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planIndexation").doesNotExist());
    }

    @Test
    @DisplayName("7. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        long id = create("TD-DEL", "ASupprimer", "[\"pdf\"]", 10);
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }
}
