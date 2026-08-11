package com.ipt.ged.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
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
 * Campagne de tests du module « Espaces de travail » (Phase 1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class WorkSpaceApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;

    private static final String BASE = "/api/v1/workspaces";
    private Long workflowId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(1L).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF test");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowId = workflowRepository.save(wf).getId();
    }

    private String ws(String name, String code, Long parentId) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"employeId\":1,\"workflowId\":" + workflowId
                + (parentId != null ? ",\"parentId\":" + parentId : "") + "}";
    }

    private long create(String name, String code, Long parentId) throws Exception {
        String body = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws(name, code, parentId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body).get("id").asLong();
    }

    @Test
    @DisplayName("1. Création d'un espace de travail (201)")
    void createWorkspace() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws("Comptabilité", "WS-A", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Comptabilité"))
                .andExpect(jsonPath("$.code").value("WS-A"))
                .andExpect(jsonPath("$.status").value("ACTIF"))
                .andExpect(jsonPath("$.owner.label").value("Sara Bennani"))
                .andExpect(jsonPath("$.workflow.id").value(workflowId));
    }

    @Test
    @DisplayName("2. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("A", "WS-DUP", null);
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws("B", "WS-DUP", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("3. Nom manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"WS-X\",\"employeId\":1,\"workflowId\":" + workflowId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("4. Déplacement : un dossier ne peut pas être son propre parent (400)")
    void moveSelfParent() throws Exception {
        long id = create("Racine", "WS-R", null);
        mvc.perform(patch(BASE + "/" + id + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":" + id + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Déplacement : interdit dans sa propre descendance (400)")
    void moveIntoDescendant() throws Exception {
        long parent = create("Parent", "WS-P", null);
        long child = create("Enfant", "WS-C", parent);
        mvc.perform(patch(BASE + "/" + parent + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":" + child + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Déplacement valide (200)")
    void moveValid() throws Exception {
        long a = create("A", "WS-MA", null);
        long b = create("B", "WS-MB", null);
        mvc.perform(patch(BASE + "/" + b + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":" + a + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.id").value((int) a));
    }

    @Test
    @DisplayName("7. Archivage : ACTIF <-> ARCHIVE")
    void archiveToggle() throws Exception {
        long id = create("Arch", "WS-ARCH", null);
        mvc.perform(patch(BASE + "/" + id + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVE"));
        mvc.perform(patch(BASE + "/" + id + "/archive"))
                .andExpect(jsonPath("$.status").value("ACTIF"));
    }

    @Test
    @DisplayName("8. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        long id = create("Del", "WS-DEL", null);
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }

    @Test
    @DisplayName("9. Arbre : racines + enfants imbriqués")
    void tree() throws Exception {
        long root = create("Root", "WS-ROOT", null);
        create("Sub", "WS-SUB", root);
        mvc.perform(get(BASE + "/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..name", hasItems("Root", "Sub")));
    }
}
