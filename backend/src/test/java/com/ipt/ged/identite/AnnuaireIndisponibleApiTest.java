package com.ipt.ged.identite;

import com.ipt.ged.identite.annuaire.SondeAnnuaire;
import com.ipt.ged.identite.evenement.ConnexionEchouee;
import com.ipt.ged.identite.evenement.MotifEchecConnexion;
import com.ipt.ged.support.Comptes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Annuaire injoignable (§3.3) : nouvelle connexion impossible avec un message
 * explicite (503), aucun mode dégradé, sonde de santé « annuaire » à DOWN.
 * Les deux contrôleurs déclarés pointent sur des ports où rien n'écoute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
@TestPropertySource(properties = {
        "ged.identite.annuaire.urls=ldap://localhost:1,ldap://localhost:2",
        "ged.identite.annuaire.delai-connexion=1s"})
class AnnuaireIndisponibleApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private SondeAnnuaire sonde;
    @Autowired private ApplicationEvents evenements;

    @Test
    @DisplayName("Aucun contrôleur ne répond : 503 ANNUAIRE_INDISPONIBLE, événement d'échec, sonde DOWN")
    void annuaireIndisponible() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr("10.40.0.1"); return r; })
                        .content("{\"identifiant\":\"" + Comptes.ADMIN + "\",\"motDePasse\":\"" + Comptes.MOT_DE_PASSE + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANNUAIRE_INDISPONIBLE"));
        assertEquals(1, evenements.stream(ConnexionEchouee.class)
                .filter(e -> e.motif() == MotifEchecConnexion.ANNUAIRE_INDISPONIBLE).count());
        assertEquals(Status.DOWN, sonde.health().getStatus());
    }
}
