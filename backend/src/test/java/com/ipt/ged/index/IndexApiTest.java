package com.ipt.ged.index;

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
 * Campagne de tests du module « Index » : CRUD, unicité, corbeille et
 * normalisation des valeurs (conservées seulement pour le type LISTE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class IndexApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;

    private static final String BASE = "/api/v1/indices";

    private String body(String code, String nom, String type, String valeurs) {
        return "{\"code\":\"" + code + "\",\"nomIndex\":\"" + nom + "\",\"fieldType\":\"" + type + "\""
                + (valeurs != null ? ",\"valeurs\":\"" + valeurs + "\"" : "")
                + ",\"obligatoire\":false,\"indexePourRecherche\":true,\"indexDeGroupage\":false}";
    }

    private UUID create(String code, String nom, String type, String valeurs) throws Exception {
        String res = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body(code, nom, type, valeurs)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    @Test
    @DisplayName("1. Création d'un index type LISTE avec valeurs (201)")
    void createListe() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content(body("IDX-1", "Priorite", "LISTE", "Basse,Haute")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("IDX-1"))
                .andExpect(jsonPath("$.fieldType").value("LISTE"))
                .andExpect(jsonPath("$.valeurs").value("Basse,Haute"));
    }

    @Test
    @DisplayName("2. Valeurs ignorées si le type n'est pas LISTE")
    void valeursClearedForNonListe() throws Exception {
        UUID id = create("IDX-TXT", "Fournisseur", "TEXTE", "ignoré1,ignoré2");
        mvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.fieldType").value("TEXTE"))
                .andExpect(jsonPath("$.valeurs").doesNotExist());
    }

    @Test
    @DisplayName("3. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("IDX-DUP", "Un", "TEXTE", null);
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(body("IDX-DUP", "Deux", "TEXTE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("4. Nom manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"IDX-X\",\"fieldType\":\"TEXTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erreurs.nomIndex").exists());
    }

    @Test
    @DisplayName("5. Mise à jour : changer le type vide les valeurs")
    void updateChangesType() throws Exception {
        UUID id = create("IDX-UP", "Champ", "LISTE", "A,B,C");
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(body("IDX-UP", "Champ", "TEXTE", "A,B,C")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldType").value("TEXTE"))
                .andExpect(jsonPath("$.valeurs").doesNotExist());
    }

    @Test
    @DisplayName("6. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        UUID id = create("IDX-DEL", "ASupprimer", "TEXTE", null);
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }
}
