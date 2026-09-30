package com.ipt.ged.cycledevie;

import com.fasterxml.jackson.databind.JsonNode;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.support.Comptes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithUserDetails;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-059 (§6.1.4) : vérification d'intégrité <b>à la demande</b>, en plus de la
 * tâche mensuelle ; réservée à l'Administrateur, tracée au journal d'audit
 * ({@code INTEGRITE_VERIFIEE}), divergence signalée ({@code INTEGRITE_ANOMALIE}).
 * Scénario de la recette CR-E5-04 : déposer, altérer, lancer la vérification.
 */
class IntegriteALaDemandeApiTest extends BaseCycleDeVieApiTest {

    private UUID type;

    @BeforeEach
    void preparer() {
        type = type(espace("Intégrité " + suffixe, null), "pdf");
    }

    private long audits(String action, UUID objet, String resultat) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = ? AND objet_id = ? AND resultat = ?",
                Long.class, action, objet, resultat);
    }

    @Test
    @DisplayName("Document : conforme puis altéré ; bilan par fichier, demande tracée, anomalie signalée")
    void document() throws Exception {
        UUID doc = deposer(type, "releve", "releve.pdf", "application/pdf", Echantillons.pdf());
        UUID fichier = fichierCourant(doc);

        JsonNode sain = json(mvc.perform(post("/api/v1/admin/integrite/documents/" + doc)).andExpect(status().isOk()));
        assertTrue(sain.get("conforme").asBoolean());
        assertEquals(1, sain.get("fichiers").size());
        assertEquals("CONFORME", sain.get("fichiers").get(0).get("statut").asText());
        assertEquals(1, audits("INTEGRITE_VERIFIEE", doc, "SUCCES"));
        assertEquals(Comptes.ADMIN, jdbc.queryForObject("SELECT acteur_nom FROM journal_audit "
                + "WHERE action = 'INTEGRITE_VERIFIEE' AND objet_id = ?", String.class, doc));

        // Altération du fichier chiffré sur disque : la vérification à la demande la détecte.
        Path chiffre = fichierChiffre(fichier);
        byte[] octets = Files.readAllBytes(chiffre);
        octets[octets.length - 20] ^= 0x5A;
        Files.write(chiffre, octets);
        JsonNode altere = json(mvc.perform(post("/api/v1/admin/integrite/documents/" + doc)).andExpect(status().isOk()));
        assertFalse(altere.get("conforme").asBoolean());
        assertEquals("ALTERE", altere.get("fichiers").get(0).get("statut").asText());
        assertEquals(1, audits("INTEGRITE_VERIFIEE", doc, "ECHEC"));
        assertEquals(1, audits("INTEGRITE_ANOMALIE", fichier, "ECHEC"));

        mvc.perform(post("/api/v1/admin/integrite/documents/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(Comptes.SECOND_ACTEUR)
    @DisplayName("Réservée : un utilisateur standard est refusé (403), rien n'est vérifié")
    void reservee() throws Exception {
        UUID doc = UUID.randomUUID();
        mvc.perform(post("/api/v1/admin/integrite/documents/" + doc)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/integrite/verification")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/integrite/verification")).andExpect(status().isForbidden());
        assertEquals(0, audits("INTEGRITE_VERIFIEE", doc, "SUCCES"));
    }

    @Test
    @DisplayName("Fonds entier : passe lancée en tâche de fond (202), tracée, état et bilan consultables")
    void fonds() throws Exception {
        deposer(type, "fonds", "fonds.pdf", "application/pdf", Echantillons.pdf());
        long avant = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'INTEGRITE_VERIFIEE' "
                + "AND objet_id IS NULL", Long.class);
        Instant lancee = Instant.now();
        mvc.perform(post("/api/v1/admin/integrite/verification")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enCours").value(true));
        assertEquals(avant + 1, jdbc.queryForObject("SELECT count(*) FROM journal_audit "
                + "WHERE action = 'INTEGRITE_VERIFIEE' AND objet_id IS NULL", Long.class));

        JsonNode etat;
        Instant limite = Instant.now().plus(Duration.ofMinutes(2));
        do {
            Thread.sleep(100);
            etat = json(mvc.perform(get("/api/v1/admin/integrite/verification")).andExpect(status().isOk()));
        } while (etat.get("enCours").asBoolean() && Instant.now().isBefore(limite));
        assertFalse(etat.get("enCours").asBoolean(), "passe terminée");
        assertFalse(Instant.parse(etat.get("fin").asText()).isBefore(lancee));
        assertTrue(etat.get("bilan").path("CONFORME").asInt() >= 1, etat.toString());
    }
}
