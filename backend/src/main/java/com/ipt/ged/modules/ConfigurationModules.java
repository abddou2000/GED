package com.ipt.ged.modules;

import com.ipt.ged.cleapi.FiltreCleApi;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Fermeture des points d'entrée d'un module désactivé (T-088, DAT §9.3).
 *
 * <p>Le filtre passe avant la chaîne de sécurité : un module non déployé
 * répond de la même façon à tous (404 {@code MODULE_INACTIF}), sans
 * authentification ni trace de refus de droits — la route « n'existe pas » sur
 * cet environnement. Désactiver le module d'intégration ferme aussi tout appel
 * par clé d'API ({@code X-API-Key}), quelle que soit la route.
 */
@Configuration
public class ConfigurationModules {

    public static final String MODULE_INACTIF = "MODULE_INACTIF";

    /** Juste avant la chaîne Spring Security, après le filtre de contexte de journalisation. */
    static final int ORDRE_FILTRE = SecurityProperties.DEFAULT_FILTER_ORDER - 5;

    private static final Logger journal = LoggerFactory.getLogger(ConfigurationModules.class);

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> filtreModules(ModulesActifs modules,
                                                                      ReponsesSecuriteProblem reponses) {
        modules.etats().forEach((m, actif) -> {
            if (!actif) journal.warn("Module « {} » ({}) INACTIF sur cet environnement", m.libelle(), m.code());
        });
        OncePerRequestFilter filtre = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                            FilterChain suite) throws ServletException, IOException {
                String chemin = requete.getRequestURI().substring(requete.getContextPath().length());
                Optional<ModuleMetier> ferme = modules.inactifPour(chemin);
                if (ferme.isEmpty() && requete.getHeader(FiltreCleApi.ENTETE_CLE) != null
                        && !modules.actif(ModuleMetier.INTEGRATION)) {
                    ferme = Optional.of(ModuleMetier.INTEGRATION);
                }
                if (ferme.isPresent()) {
                    reponses.ecrire(requete, reponse, HttpStatus.NOT_FOUND, MODULE_INACTIF,
                            "Module « " + ferme.get().libelle() + " » non activé sur cet environnement.");
                    return;
                }
                suite.doFilter(requete, reponse);
            }
        };
        var enregistrement = new FilterRegistrationBean<>(filtre);
        enregistrement.setOrder(ORDRE_FILTRE);
        enregistrement.setName("filtreModules");
        return enregistrement;
    }

    /** Jauge {@code ged_module_actif{module}} : 1 actif, 0 inactif (supervision de l'UAT et de la production). */
    @Bean
    public MeterBinder metriquesModules(ModulesActifs modules) {
        return (MeterRegistry registre) -> {
            for (ModuleMetier m : ModuleMetier.values()) {
                Gauge.builder("ged.module.actif", modules, x -> x.actif(m) ? 1 : 0)
                        .description("Module métier activé sur cet environnement (DAT §9.3)")
                        .tag("module", m.code())
                        .register(registre);
            }
        };
    }
}
