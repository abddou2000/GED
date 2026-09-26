package com.ipt.ged.etiquette;

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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Campagne de tests du module « Étiquette » : CRUD, unicité, corbeille.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class EtiquetteApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;

    private static final String BASE = "/api/v1/etiquettes";

    private String body(String code, String tag, String couleur) {
        return "{\"code\":\"" + code + "\",\"tag\":\"" + tag + "\",\"couleur\":\"" + couleur + "\"}";
    }

    private UUID create(String code, String tag, String couleur) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body(code, tag, couleur)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    @Test
    @DisplayName("1. Création d'une étiquette (201)")
    void createEtiquette() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body("TAG-1", "Urgent", "#c0392b")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tag").value("Urgent"))
                .andExpect(jsonPath("$.couleur").value("#c0392b"));
    }

    @Test
    @DisplayName("2. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("TAG-DUP", "Un", "#000000");
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body("TAG-DUP", "Deux", "#111111")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("3. Libellé manquant refusé (400)")
    void missingTag() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content("{\"code\":\"TAG-X\",\"couleur\":\"#fff\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tag").exists());
    }

    @Test
    @DisplayName("4. Couleur manquante refusée (400)")
    void missingColor() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content("{\"code\":\"TAG-Y\",\"tag\":\"Y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.couleur").exists());
    }

    @Test
    @DisplayName("5. Mise à jour du libellé et de la couleur")
    void update() throws Exception {
        UUID id = create("TAG-UP", "Avant", "#000000");
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON).content(body("TAG-UP", "Apres", "#1e7a46")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("Apres"))
                .andExpect(jsonPath("$.couleur").value("#1e7a46"));
    }

    @Test
    @DisplayName("6. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        UUID id = create("TAG-DEL", "ASupprimer", "#000000");
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }
}
