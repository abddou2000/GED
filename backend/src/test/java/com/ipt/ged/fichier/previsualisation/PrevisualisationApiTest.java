package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.fichier.DepotClesFichierMemoire;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.DepotClesFichier;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.support.Comptes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * §6.1.6 — point d'entrée de prévisualisation, branché sur un résolveur de
 * test (le modèle version_document UUID n'est pas encore intégré).
 */
@SpringBootTest(properties = "ged.fichiers.previsualisation.api-active=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class PrevisualisationApiTest {

    /** Versions connues du résolveur de test. */
    static final Map<String, ResolveurFichierVersion.FichierVersion> VERSIONS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Branchements {
        @Bean
        @Primary
        DepotClesFichier depotClesMemoire() {
            return new DepotClesFichierMemoire();
        }

        @Bean
        ResolveurFichierVersion resolveurDeTest() {
            return versionId -> Optional.ofNullable(VERSIONS.get(versionId));
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private StockageChiffre stockage;
    @Autowired private ApplicationEvents evenements;

    private String versionPdf(byte[] pdf) {
        UUID fichier = stockage.ecrire(new ByteArrayInputStream(pdf), 10_000_000).id();
        String versionId = UUID.randomUUID().toString();
        VERSIONS.put(versionId, new ResolveurFichierVersion.FichierVersion(versionId, "doc-1", fichier,
                FormatsReconnus.PDF, "Rapport annuel.pdf"));
        return versionId;
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("PDF servi inline, déchiffré en flux, sans cache, avec événement d'audit distinct")
    void apercuPdf() throws Exception {
        byte[] pdf = Echantillons.pdf();
        String versionId = versionPdf(pdf);

        MvcResult asynchrone = mvc.perform(get("/api/v1/versions/{id}/apercu", versionId))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult r = mvc.perform(asyncDispatch(asynchrone))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Length", String.valueOf(pdf.length)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andReturn();
        assertArrayEquals(pdf, r.getResponse().getContentAsByteArray());

        assertEquals(1, evenements.stream(PrevisualisationController.ApercuConsulte.class)
                .filter(e -> e.versionId().equals(versionId))
                .filter(e -> Comptes.ADMIN.equals(e.utilisateur()))
                .count());
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Version inconnue : 404 au format commun, avec le code FICHIER_INTROUVABLE")
    void versionInconnue() throws Exception {
        mvc.perform(get("/api/v1/versions/{id}/apercu", "inexistante"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("FICHIER_INTROUVABLE"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Format sans aperçu : 415 APERCU_NON_DISPONIBLE, aucun événement de consultation")
    void formatSansApercu() throws Exception {
        UUID fichier = stockage.ecrire(new ByteArrayInputStream(new byte[]{1, 2}), 100).id();
        VERSIONS.put("zip", new ResolveurFichierVersion.FichierVersion("zip", "doc-2", fichier, "application/zip", "a.zip"));
        mvc.perform(get("/api/v1/versions/{id}/apercu", "zip"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("APERCU_NON_DISPONIBLE"));
        assertEquals(0, evenements.stream(PrevisualisationController.ApercuConsulte.class)
                .filter(e -> e.versionId().equals("zip")).count());
    }

    @Test
    @DisplayName("Anonyme : 401, rien n'est déchiffré")
    void anonyme() throws Exception {
        String versionId = versionPdf(Echantillons.pdf());
        mvc.perform(get("/api/v1/versions/{id}/apercu", versionId))
                .andExpect(status().isUnauthorized());
    }
}
