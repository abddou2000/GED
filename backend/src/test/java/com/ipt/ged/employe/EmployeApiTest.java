package com.ipt.ged.employe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liste des personnes : chacune porte son identité GED, que la désignation
 * d'un document confidentiel, le critère « déposant » de la recherche et
 * l'auteur d'une version emploient (ANO-F-012, ANO-F-013).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmployeApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private IdentitesDeTest identites;
    @Autowired private com.ipt.ged.support.JeuDroits jeu;

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("ANO-F-013 : GET /employes?has_user=1 donne à un utilisateur standard l'identité GED de chaque personne")
    void identiteGedExposee() throws Exception {
        // Identité GED du second acteur (provisionnée à la première connexion).
        var second = (com.ipt.ged.security.UtilisateurConnecte) identites.loadUserByUsername(Comptes.SECOND_ACTEUR);
        jeu.habiliter(Comptes.SANS_ROLE, com.ipt.ged.identite.Role.UTILISATEUR_STANDARD, jeu.noeud("Espace F-013", null), null);
        JsonNode liste = om.readTree(mvc.perform(get("/api/v1/employes").param("has_user", "1")
                        .with(user(identites.loadUserByUsername(Comptes.SANS_ROLE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String employe = second.getEmployeId().toString();
        JsonNode trouve = null;
        for (JsonNode e : liste) if (e.get("id").asText().equals(employe)) trouve = e;
        assertNotNull(trouve, "le second acteur a un compte : il est listé");
        assertEquals(second.getUtilisateurId().toString(), trouve.get("utilisateurId").asText());
    }
}
