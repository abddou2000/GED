package com.ipt.ged.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Campagne de tests du module « Regles de Workflow ».
 * Base H2 en memoire ; chaque test est transactionnel (rollback apres execution).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkflowApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private static final String BASE = "/api/v1/workflowgeds";

    /* -------- helpers JSON -------- */
    private String step(long emp, String label, int order) {
        return "{\"employeId\":" + emp + ",\"label\":\"" + label + "\",\"stepOrder\":" + order + "}";
    }

    private String wf(String name, String... steps) {
        return "{\"name\":\"" + name + "\",\"steps\":[" + String.join(",", steps) + "]}";
    }

    private long create(String json) throws Exception {
        String body = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body).get("id").asLong();
    }

    // ---------------------------------------------------------------- Employes

    @Test
    @DisplayName("1. Seuls les employes avec compte sont proposes comme approbateurs")
    void employesWithAccount() throws Exception {
        mvc.perform(get("/api/v1/employes").param("has_user", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].fullName", not(hasItem("Omar Tazi"))));
    }

    // ---------------------------------------------------------------- Creation

    @Test
    @DisplayName("2. Creation d'un circuit avec etapes ordonnees (201)")
    void createWorkflow() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(wf("Validation facture", step(1, "Comptable", 1), step(2, "Directeur", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Validation facture"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].employeFullName").value("Sara Bennani"))
                .andExpect(jsonPath("$.steps[0].stepOrder").value(1))
                .andExpect(jsonPath("$.steps[1].stepOrder").value(2));
    }

    @Test
    @DisplayName("3. Refus si le nom est vide (400 + message)")
    void createRejectsEmptyName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(wf("", step(1, "X", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    @DisplayName("4. Refus si aucune etape (400 + message)")
    void createRejectsNoSteps() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(wf("Circuit vide")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.steps").exists());
    }

    // ---------------------------------------------------------------- Lecture

    @Test
    @DisplayName("5. La liste est paginee (content + total + page + size)")
    void listIsPaginated() throws Exception {
        create(wf("Alpha", step(1, "A", 1)));
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @DisplayName("6. La recherche filtre par nom")
    void searchFiltersByName() throws Exception {
        create(wf("Contrat vente", step(1, "A", 1)));
        create(wf("Facture achat", step(2, "B", 1)));
        mvc.perform(get(BASE).param("search", "contrat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Contrat vente"));
    }

    @Test
    @DisplayName("7. Detail d'un id inexistant -> 404")
    void getMissingReturns404() throws Exception {
        mvc.perform(get(BASE + "/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ---------------------------------------------------------------- Edition

    @Test
    @DisplayName("8. L'edition REMPLACE les etapes (2 -> 1) et renumerote")
    void updateReplacesSteps() throws Exception {
        long id = create(wf("Circuit", step(1, "Etape1", 1), step(2, "Etape2", 2)));
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(wf("Circuit modifie", step(3, "Unique", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Circuit modifie"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].employeFullName").value("Yasmine Alaoui"))
                .andExpect(jsonPath("$.steps[0].stepOrder").value(1));
    }

    // ---------------------------------------------------------------- Corbeille

    @Test
    @DisplayName("9. Suppression = corbeille (sort de l'actif, entre dans /trashed)")
    void softDeleteMovesToTrash() throws Exception {
        long id = create(wf("A supprimer", step(1, "X", 1)));
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());

        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) id))));
        mvc.perform(get(BASE + "/trashed"))
                .andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }

    @Test
    @DisplayName("10. Restauration : l'element revient dans la liste active")
    void restoreBringsBack() throws Exception {
        long id = create(wf("A restaurer", step(1, "X", 1)));
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.content[*].id", hasItem((int) id)));
    }

    @Test
    @DisplayName("11. Suppression puis restauration multiples")
    void multipleDeleteAndRestore() throws Exception {
        long a = create(wf("M1", step(1, "X", 1)));
        long b = create(wf("M2", step(2, "Y", 1)));
        String ids = "{\"ids\":[" + a + "," + b + "]}";

        mvc.perform(delete(BASE + "/multiple-delete").contentType(APPLICATION_JSON).content(ids))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.content[*].id", allOf(not(hasItem((int) a)), not(hasItem((int) b)))));

        mvc.perform(patch(BASE + "/multiple-restore").contentType(APPLICATION_JSON).content(ids))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.content[*].id", allOf(hasItem((int) a), hasItem((int) b))));
    }
}
