package com.ipt.ged.planindexation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Campagne de tests du module « Plan d'indexation » : CRUD, unicité, corbeille,
 * et surtout le regroupement ORDONNÉ des index + l'aperçu du nommage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class PlanIndexationApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private IndexRepository indexRepo;

    private static final String BASE = "/api/v1/plan-indexations";
    private UUID idxAlpha;
    private UUID idxBeta;

    @BeforeEach
    void setup() {
        idxAlpha = indexRepo.save(index("IDX-A", "Alpha")).getId();
        idxBeta = indexRepo.save(index("IDX-B", "Beta")).getId();
    }

    private IndexField index(String code, String nom) {
        IndexField x = new IndexField(code, nom);
        x.setFieldType(IndexFieldType.TEXTE);
        return x;
    }

    private String body(String code, String nom, boolean majuscule, String sep, String indexIds) {
        return "{\"code\":\"" + code + "\",\"nomDuPlan\":\"" + nom + "\",\"modeIndexation\":true,"
                + "\"manuel\":false,\"majuscule\":" + majuscule + ",\"separateur\":\"" + sep + "\","
                + "\"indexIds\":" + indexIds + "}";
    }

    private UUID create(String code, String nom, boolean majuscule, String sep, String indexIds) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body(code, nom, majuscule, sep, indexIds)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    @Test
    @DisplayName("1. Création avec index ordonnés + aperçu du nommage (201)")
    void createWithIndices() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("PL-1", "Fiche A", false, "-", "[\"" + idxAlpha + "\",\"" + idxBeta + "\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nomDuPlan").value("Fiche A"))
                .andExpect(jsonPath("$.indexCount").value(2))
                .andExpect(jsonPath("$.indices[0].label").value("Alpha"))
                .andExpect(jsonPath("$.indices[1].label").value("Beta"))
                .andExpect(jsonPath("$.preview").value("alpha-beta"));
    }

    @Test
    @DisplayName("2. L'ordre des index est préservé + majuscule appliquée")
    void orderPreservedAndUppercase() throws Exception {
        UUID id = create("PL-ORD", "Ordre", true, "_", "[\"" + idxBeta + "\",\"" + idxAlpha + "\"]");
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.indices[0].label").value("Beta"))
                .andExpect(jsonPath("$.indices[1].label").value("Alpha"))
                .andExpect(jsonPath("$.preview").value("BETA_ALPHA"));
    }

    @Test
    @DisplayName("ANO-F-019 : jetons système libellés en français, dans la liste et dans l'aperçu")
    void jetonsSystemeEnFrancais() throws Exception {
        mvc.perform(get(BASE + "/jetons-systeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains("date", "houres", "months", "days", "year")))
                .andExpect(jsonPath("$[*].name", contains("DATE", "HEURE", "MOIS", "JOUR", "ANNÉE")));

        String corps = "{\"code\":\"PL-FR\",\"nomDuPlan\":\"Charte\",\"modeIndexation\":true,"
                + "\"manuel\":false,\"majuscule\":true,\"separateur\":\"_\","
                + "\"indexIds\":[\"" + idxAlpha + "\"],"
                + "\"charteIds\":[\"" + idxAlpha + "\",\"year\",\"months\",\"days\",\"houres\"]}";
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.preview").value("ALPHA_ANNÉE_MOIS_JOUR_HEURE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(om.readTree(res).get("id").asText());
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.preview", not(containsString("YEAR"))))
                .andExpect(jsonPath("$.preview").value("ALPHA_ANNÉE_MOIS_JOUR_HEURE"));
    }

    @Test
    @DisplayName("3. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("PL-DUP", "Un", false, "-", "[]");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body("PL-DUP", "Deux", false, "-", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("4. Nom du plan manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"PL-X\",\"separateur\":\"-\",\"indexIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erreurs.nomDuPlan").exists());
    }

    @Test
    @DisplayName("5. Mise à jour : remplace les index")
    void updateReplacesIndices() throws Exception {
        UUID id = create("PL-UP", "Avant", false, "-", "[\"" + idxAlpha + "\"]");
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(body("PL-UP", "Apres", false, "-", "[\"" + idxBeta + "\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexCount").value(1))
                .andExpect(jsonPath("$.indices[0].label").value("Beta"));
    }

    @Test
    @DisplayName("6. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        UUID id = create("PL-DEL", "ASupprimer", false, "-", "[]");
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }
}
