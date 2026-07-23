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
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Campagne de tests du module « Groupe d'accès » : CRUD, unicité, corbeille,
 * et surtout la normalisation en cascade des 8 droits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccessGroupApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;

    private static final String BASE = "/api/v1/access-groups";
    private static final String[] KEYS = {
            "access", "lecture", "modifier", "uploader",
            "supprimer", "deplacer", "ajouterVersion", "verrouillerDeverrouiller"
    };

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

    private String rights(String... trueKeys) {
        Set<String> on = Set.of(trueKeys);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < KEYS.length; i++) {
            sb.append('"').append(KEYS[i]).append("\":").append(on.contains(KEYS[i]));
            if (i < KEYS.length - 1) sb.append(',');
        }
        return sb.append('}').toString();
    }

    private String body(String code, String name, String rightsJson, String wsIds, String userIds) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"rights\":" + rightsJson
                + ",\"workspaceIds\":" + wsIds + ",\"userIds\":" + userIds + "}";
    }

    private long create(String code, String name, String rightsJson, String wsIds, String userIds) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body(code, name, rightsJson, wsIds, userIds)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(res).get("id").asLong();
    }

    /* ---------- tests ---------- */

    @Test
    @DisplayName("1. Création avec droits, workspaces et membres (201)")
    void createGroup() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-1", "Groupe A", rights("lecture"), "[" + workspaceId + "]", "[1,2]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("AG-1"))
                .andExpect(jsonPath("$.name").value("Groupe A"))
                .andExpect(jsonPath("$.workspacesCount").value(1))
                .andExpect(jsonPath("$.usersCount").value(2))
                .andExpect(jsonPath("$.rights.lecture").value(true))
                .andExpect(jsonPath("$.rights.access").value(true)); // lecture ⟹ access
    }

    @Test
    @DisplayName("2. Cascade des droits : supprimer ⟹ modifier, uploadé, lecture, accès")
    void rightsCascade() throws Exception {
        long id = create("AG-CASC", "Cascade", rights("supprimer"), "[]", "[1]");
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.rights.supprimer").value(true))
                .andExpect(jsonPath("$.rights.modifier").value(true))
                .andExpect(jsonPath("$.rights.uploader").value(true))
                .andExpect(jsonPath("$.rights.lecture").value(true))
                .andExpect(jsonPath("$.rights.access").value(true))
                .andExpect(jsonPath("$.rights.deplacer").value(false))
                .andExpect(jsonPath("$.rights.verrouillerDeverrouiller").value(false));
    }

    @Test
    @DisplayName("3. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("AG-DUP", "Un", rights("lecture"), "[]", "[1]");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-DUP", "Deux", rights("lecture"), "[]", "[1]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("4. Nom en double refusé (400)")
    void duplicateName() throws Exception {
        create("AG-N1", "MemeNom", rights("lecture"), "[]", "[1]");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("AG-N2", "MemeNom", rights("lecture"), "[]", "[1]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("5. Nom manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"AG-X\",\"rights\":" + rights() + ",\"workspaceIds\":[],\"userIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("6. Mise à jour : remplace droits et membres")
    void updateReplacesMembers() throws Exception {
        long id = create("AG-UP", "AvantMaj", rights("lecture"), "[]", "[1]");
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(body("AG-UP", "ApresMaj", rights("modifier"), "[" + workspaceId + "]", "[2,3]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ApresMaj"))
                .andExpect(jsonPath("$.usersCount").value(2))
                .andExpect(jsonPath("$.workspacesCount").value(1))
                .andExpect(jsonPath("$.rights.modifier").value(true))
                .andExpect(jsonPath("$.rights.uploader").value(true)); // modifier ⟹ uploadé
    }

    @Test
    @DisplayName("7. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        long id = create("AG-DEL", "ASupprimer", rights("lecture"), "[]", "[1]");
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }
}
