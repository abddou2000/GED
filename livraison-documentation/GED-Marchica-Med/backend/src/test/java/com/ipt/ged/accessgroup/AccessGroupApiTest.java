package com.ipt.ged.accessgroup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
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
 * Campagne de tests du module « Groupe d'accès » : CRUD, unicité, corbeille et
 * rattachement des espaces / membres. Le groupe n'expose plus aucun droit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class AccessGroupApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;

    private static final String BASE = "/api/v1/access-groups";

    private Long workspaceId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(1L).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF test");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-AG");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();
    }

    /* ---------- helpers ---------- */

    private String body(String code, String name, String wsIds, String userIds) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\""
                + ",\"workspaceIds\":" + wsIds + ",\"userIds\":" + userIds + "}";
    }

    private long create(String code, String name, String wsIds, String userIds) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body(code, name, wsIds, userIds)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(res).get("id").asLong();
    }

    /* ---------- tests ---------- */

    @Test
    @DisplayName("1. Création avec workspaces et membres (201)")
    void createGroup() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-1", "Groupe A", "[" + workspaceId + "]", "[1,2]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("AG-1"))
                .andExpect(jsonPath("$.name").value("Groupe A"))
                .andExpect(jsonPath("$.workspacesCount").value(1))
                .andExpect(jsonPath("$.usersCount").value(2))
                .andExpect(jsonPath("$.rights").doesNotExist());
    }

    @Test
    @DisplayName("3. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("AG-DUP", "Un", "[]", "[1]");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-DUP", "Deux", "[]", "[1]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("4. Nom en double refusé (400)")
    void duplicateName() throws Exception {
        create("AG-N1", "MemeNom", "[]", "[1]");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-N2", "MemeNom", "[]", "[1]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("5. Nom manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"AG-X\",\"workspaceIds\":[],\"userIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("6. Mise à jour : remplace espaces et membres")
    void updateReplacesMembers() throws Exception {
        long id = create("AG-UP", "AvantMaj", "[]", "[1]");
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(body("AG-UP", "ApresMaj", "[" + workspaceId + "]", "[2,3]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ApresMaj"))
                .andExpect(jsonPath("$.usersCount").value(2))
                .andExpect(jsonPath("$.workspacesCount").value(1));
    }

    @Test
    @DisplayName("7. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        long id = create("AG-DEL", "ASupprimer", "[]", "[1]");
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }
}
