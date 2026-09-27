package com.ipt.ged.idempotence;

import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;

/**
 * Câblage de l'idempotence : filtre placé juste après la chaîne de sécurité
 * (et après le filtre qui renseigne l'utilisateur dans le journal), purge
 * horaire des entrées expirées.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProprietesIdempotence.class)
public class ConfigurationIdempotence {

    private static final Logger journal = LoggerFactory.getLogger(ConfigurationIdempotence.class);

    /**
     * Ordre du filtre : après la sécurité (+0), le filtre MDC de l'utilisateur
     * (+1) et les conventions de l'API (+2, refus des métadonnées trop
     * volumineuses avant toute réservation).
     */
    public static final int ORDRE_FILTRE = SecurityProperties.DEFAULT_FILTER_ORDER + 3;

    private final DepotIdempotence depot;

    public ConfigurationIdempotence(DepotIdempotence depot) {
        this.depot = depot;
    }

    @Bean
    public FilterRegistrationBean<FiltreIdempotence> filtreIdempotence(ProprietesIdempotence proprietes,
                                                                       ReponsesSecuriteProblem reponses) {
        var enregistrement = new FilterRegistrationBean<>(
                new FiltreIdempotence(depot, proprietes, reponses, Clock.systemUTC()));
        enregistrement.setOrder(ORDRE_FILTRE);
        enregistrement.setName("filtreIdempotence");
        return enregistrement;
    }

    /** Purge des clés expirées (au-delà de 24 h), chaque heure. */
    @Scheduled(cron = "${ged.api.idempotence.purge-cron:0 15 * * * *}")
    public void purger() {
        int n = depot.purger(Instant.now());
        if (n > 0) journal.info("Idempotence : {} clé(s) expirée(s) purgée(s)", n);
    }
}
