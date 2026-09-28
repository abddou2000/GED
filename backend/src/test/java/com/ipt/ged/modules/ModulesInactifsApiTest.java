package com.ipt.ged.modules;

import com.ipt.ged.cleapi.FiltreCleApi;
import com.ipt.ged.document.conservation.TacheAlertesEcheance;
import com.ipt.ged.notification.DemandeNotification;
import com.ipt.ged.notification.Notifications;
import com.ipt.ged.notification.TypeNotification;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environnement UAT dont trois modules ne sont pas encore déployés (T-088,
 * DAT §9.3) : leurs routes répondent 404 {@code MODULE_INACTIF}, leurs
 * traitements de fond sont arrêtés, le socle et les autres modules restent
 * servis.
 */
@SpringBootTest(properties = {
        "ged.modules.workflow.actif=false",
        "ged.modules.cycledevie.actif=false",
        "ged.modules.notifications.actif=false",
        "ged.modules.integration.actif=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class ModulesInactifsApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private Environment environnement;
    @Autowired private ApplicationContext contexte;
    @Autowired private Notifications notifications;
    @Autowired private MeterRegistry metriques;
    @Autowired private JeuDroits jeu;

    @Test
    @DisplayName("Routes d'un module inactif : 404 MODULE_INACTIF, même sans authentification")
    void routesFermees() throws Exception {
        mvc.perform(get("/api/v1/workflow/a-traiter"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConfigurationModules.MODULE_INACTIF))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Circuits de validation")));
        mvc.perform(get("/api/v1/archivage/jobs").with(anonymous()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConfigurationModules.MODULE_INACTIF));
        mvc.perform(get("/api/v1/notifications/compteur"))
                .andExpect(jsonPath("$.code").value(ConfigurationModules.MODULE_INACTIF));
        // Intégration inactive : toute requête par clé d'API est fermée, quelle que soit la route.
        mvc.perform(get("/api/v1/documents").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, "ged_test_x_y"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConfigurationModules.MODULE_INACTIF));
    }

    @Test
    @DisplayName("Socle et modules actifs servis ; état publié par l'API et en métrique")
    void socleServi() throws Exception {
        mvc.perform(get("/api/v1/documents")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/exports")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'workflow')].actif").value(false))
                .andExpect(jsonPath("$[?(@.code == 'export')].actif").value(true))
                .andExpect(jsonPath("$[?(@.code == 'ocr')].actif").value(true));
        assertThat(metriques.get("ged.module.actif").tag("module", "workflow").gauge().value()).isZero();
        assertThat(metriques.get("ged.module.actif").tag("module", "export").gauge().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("Traitements de fond arrêtés : alertes d'échéance, écriture et expédition des notifications")
    void traitementsArretes() {
        assertThat(environnement.getProperty("ged.conservation.alertes.actif")).isEqualTo("false");
        assertThat(contexte.getBeanNamesForType(TacheAlertesEcheance.class)).isEmpty();
        assertThat(environnement.getProperty("ged.notification.expedition-auto")).isEqualTo("false");
        List<UUID> ecrites = notifications.envoyer(DemandeNotification.a(TypeNotification.CIRCUIT_OUVERT,
                List.of(jeu.utilisateurId(Comptes.SECOND_ACTEUR)), "DOCUMENT", UUID.randomUUID(), Map.of(), "/"));
        assertThat(ecrites).isEmpty();
    }
}
