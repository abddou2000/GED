package com.ipt.ged.supervision;

import com.ipt.ged.journalisation.FiltreContexteRequete;
import com.ipt.ged.journalisation.FiltreUtilisateurJournalisation;
import com.ipt.ged.ocr.file.MetriquesOcr;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    @DisplayName("T-074 : chaque métrique de la GED citée par une règle d'alerte est bien publiée (dont la jauge par contrôleur, D4)")
    void alertesSurDesMetriquesPubliees() throws Exception {
        String regles = Files.readString(Path.of("../deploiement/prometheus/alertes.yml"), StandardCharsets.UTF_8);
        Set<String> citees = new TreeSet<>();
        Matcher m = Pattern.compile("\\bged_[a-z_]+").matcher(regles);
        while (m.find()) citees.add(m.group());
        assertThat(citees).contains("ged_annuaire_controleur", "ged_sante");

        // La chaîne OCR est inactive dans le profil de test (ged.ocr.chaine.actif) :
        // ses métriques sont publiées par les mêmes classes que dans le contexte
        // d'exploitation, dans un registre à part.
        PrometheusMeterRegistry chaineOcr = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FileDeTraitement fileOcr = new FileDeTraitement() {
            @Override public String nom() { return "ocr"; }
            @Override public long profondeur() { return 1; }
            @Override public Optional<Duration> ageDuPlusAncien() { return Optional.of(Duration.ofMinutes(3)); }
        };
        new MetriquesSupervision(new StaticListableBeanFactory().getBeanProvider(HealthEndpoint.class),
                new SondeReferentielFichiers(".", 0.95), List.of(fileOcr), Duration.ofHours(24)).bindTo(chaineOcr);
        new MetriquesOcr(chaineOcr, Duration.ofHours(24), Clock.systemUTC());

        String sortie = prometheus.scrape() + chaineOcr.scrape();
        // Nom de la série telle que Prometheus la lit : suffixes d'histogramme retirés
        // pour les séries qui n'apparaissent qu'après une première mesure.
        List<String> absentes = citees.stream()
                .filter(nom -> !sortie.contains(nom) && !sortie.contains(nom.replaceFirst("_(bucket|count|sum)$", "")))
                .toList();
        assertThat(absentes).as("règle d'alerte sur une métrique jamais publiée").isEmpty();
    }

    @Test
    @DisplayName("Actuator sur un port de management séparé, lié à l'interface locale par défaut")
    void portDeManagement() throws Exception {
        // Valeurs par défaut du fichier livré, lues avant résolution : chaque poste
        // de l'équipe déplace le port par GED_MANAGEMENT_PORT (brief, règle 6), et
        // comparer la valeur résolue à 8081 rendait le test dépendant du poste.
        PropertySource<?> livre = new YamlPropertySourceLoader()
                .load("exploitation.yml", new ClassPathResource("exploitation.yml")).get(0);
        assertThat(livre.getProperty("management.server.port")).hasToString("${GED_MANAGEMENT_PORT:8081}");
        assertThat(livre.getProperty("management.server.address")).hasToString("${GED_MANAGEMENT_ADRESSE:127.0.0.1}");
        assertThat(env.getProperty("management.server.port")).isNotBlank()
                .isNotEqualTo(env.getProperty("server.port", "8080"));
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
