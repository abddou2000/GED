package com.ipt.ged.supervision;

import com.ipt.ged.journalisation.FiltreContexteRequete;
import com.ipt.ged.journalisation.FiltreUtilisateurJournalisation;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Câblage de la supervision et de la journalisation dans le contexte réel de
 * l'application (même configuration de test que les autres suites, donc même
 * contexte Spring réutilisé).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupervisionIntegrationTest {

    @Autowired private HealthEndpoint sante;
    @Autowired private PrometheusMeterRegistry prometheus;
    @Autowired private Environment env;
    @Autowired private MockMvc mvc;
    @Autowired private FilterRegistrationBean<FiltreContexteRequete> filtreContexteRequete;
    @Autowired private FilterRegistrationBean<FiltreUtilisateurJournalisation> filtreUtilisateurJournalisation;
    @Autowired @Qualifier("applicationTaskExecutor") private AsyncTaskExecutor executeur;

    @AfterEach
    void nettoyer() {
        MDC.clear();
    }

    @Test
    @DisplayName("La sonde de santé couvre base, référentiel, annuaire, antivirus et files ; l'annuaire hors disponibilité (DAT 6.7, 3.3)")
    void sondesPresentes() {
        var global = (CompositeHealth) sante.health();
        assertThat(global.getComponents())
                .containsKeys("db", "referentielFichiers", "antivirus", "filesTraitement");

        var disponibilite = (CompositeHealth) sante.healthForPath("readiness");
        assertThat(disponibilite.getComponents())
                .containsKeys("db", "referentielFichiers", "antivirus", "filesTraitement")
                // Annuaire indisponible : plus de nouvelles connexions, mais les
                // sessions ouvertes travaillent ; l'instance reste en service.
                .doesNotContainKey("annuaire");
    }

    @Test
    @DisplayName("Les métriques Prometheus exposent l'état des sondes, le stockage et les requêtes HTTP")
    void metriquesPrometheus() throws Exception {
        mvc.perform(get("/api/v1/documents"));

        String sortie = prometheus.scrape();
        assertThat(sortie)
                .contains("ged_sante{")
                .contains("composant=\"db\"")
                .contains("ged_stockage_libre_bytes")
                .contains("ged_stockage_total_bytes")
                .contains("http_server_requests_seconds_bucket")
                .contains("application=\"ged\"");
        // Objectif de disponibilité en recherche : 24 h (décision D6), seuil de l'alerte OCR.
        assertThat(sortie).containsPattern("ged_ocr_objectif_disponibilite_seconds\\{[^}]*} 86400\\.0");
    }

    @Test
    @DisplayName("Actuator sur un port de management séparé, lié à l'interface locale par défaut")
    void portDeManagement() throws Exception {
        assertThat(env.getProperty("management.server.port")).isEqualTo("8081");
        assertThat(env.getProperty("management.server.port")).isNotEqualTo(env.getProperty("server.port"));
        assertThat(env.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");

        // Sur le port de l'API, les métriques ne sont pas servies à un anonyme.
        int statut = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();
        assertThat(statut).isIn(401, 404);
    }

    @Test
    @DisplayName("Les filtres MDC encadrent la chaîne Spring Security")
    void ordreDesFiltres() {
        assertThat(filtreContexteRequete.getOrder()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(filtreUtilisateurJournalisation.getOrder()).isGreaterThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        assertThat(env.getProperty("spring.security.filter.order", Integer.class, SecurityProperties.DEFAULT_FILTER_ORDER))
                .isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    @DisplayName("Rotation et rétention par défaut : 100 Mo, 90 jours ; INFO par défaut")
    void reglagesJournalisation() {
        assertThat(env.getProperty("ged.journalisation.taille-max-fichier")).isEqualTo("100MB");
        assertThat(env.getProperty("ged.journalisation.retention-jours")).isEqualTo("90");
        assertThat(env.getProperty("logging.level.root")).isEqualTo("INFO");
        assertThat(env.getProperty("logging.level.com.ipt.ged")).isEqualTo("INFO");
    }

    @Test
    @DisplayName("L'exécuteur de tâches de l'application transmet le MDC aux traitements asynchrones")
    void executeurPropageLeMdc() throws Exception {
        MDC.put("username", "a.benali");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");

        Map<String, String> vu = executeur.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);

        assertThat(vu).containsEntry("username", "a.benali")
                .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(vu.get("spanId")).isNotEqualTo("00f067aa0ba902b7");
    }
}
