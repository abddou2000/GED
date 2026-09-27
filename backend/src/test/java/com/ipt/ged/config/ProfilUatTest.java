package com.ipt.ged.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le profil UAT reproduit la mécanique du profil PROD (DAT 9.3, 10.1) : même
 * connexion, mêmes migrations, mêmes secrets par variables d'environnement.
 *
 * <p>On charge la configuration comme le fait Spring Boot au démarrage, pour
 * chacun des deux profils, et on compare les clés qui décident de la
 * mécanique. Le test ne fige aucune valeur : il reste vrai quand le socle
 * change (migration PostgreSQL et Liquibase), tant que l'UAT suit la prod.
 */
class ProfilUatTest {

    /** Familles de clés qui doivent être identiques entre UAT et PROD. */
    private static final List<String> MECANIQUE = List.of(
            "spring.datasource.", "spring.jpa.", "spring.flyway.", "spring.liquibase.",
            "spring.h2.", "ged.securite.", "ged.base.", "management.server.",
            "management.endpoints.", "logging.level.", "ged.journalisation.");

    private static StandardEnvironment charger(String profil) {
        StandardEnvironment env = new StandardEnvironment();
        // Isoler du poste : aucune variable d'environnement ni propriété système.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(),
                new DefaultBootstrapContext(), List.of(profil));
        return env;
    }

    private static Map<String, String> mecanique(StandardEnvironment env) {
        Map<String, String> cles = new TreeMap<>();
        for (var source : env.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> e) {
                for (String nom : e.getPropertyNames()) {
                    if (MECANIQUE.stream().anyMatch(nom::startsWith)) {
                        cles.putIfAbsent(nom, env.getProperty(nom));
                    }
                }
            }
        }
        return cles;
    }

    @Test
    @DisplayName("UAT et PROD résolvent la même base, les mêmes migrations et les mêmes secrets")
    void memeMecaniqueQueProd() {
        Map<String, String> prod = mecanique(charger("prod"));
        Map<String, String> uat = mecanique(charger("uat"));

        assertThat(prod).as("le profil prod définit sa connexion")
                .containsKeys("spring.datasource.url", "spring.jpa.hibernate.ddl-auto");
        assertThat(uat).isEqualTo(prod);
    }

    @Test
    @DisplayName("UAT est identifié comme tel dans les métriques")
    void etiquetteEnvironnement() {
        assertThat(charger("uat").getProperty("management.metrics.tags.environnement")).isEqualTo("uat");
    }
}
