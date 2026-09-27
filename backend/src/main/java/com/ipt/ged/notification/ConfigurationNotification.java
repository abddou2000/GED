package com.ipt.ged.notification;

import com.ipt.ged.common.ActeurCourant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Câblage des notifications et implémentations par défaut des points
 * d'extension, remplacées par les lots identité et autorisation (dev1) en
 * déclarant leur propre bean.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProprietesNotification.class)
public class ConfigurationNotification {

    @Bean
    public ModelesNotification modelesNotification() {
        return new ModelesNotification();
    }

    /** Cache d'annuaire (E2), groupes et habilitations (E3). */
    @Bean
    @ConditionalOnMissingBean(AnnuaireDestinataires.class)
    public AnnuaireDestinataires annuaireDestinatairesIdentite(JdbcTemplate jdbc) {
        return new AnnuaireDestinatairesIdentite(jdbc);
    }

    /** Identité GED du jeton (lot E2). */
    @Bean
    @ConditionalOnMissingBean(IdentiteDestinataire.class)
    public IdentiteDestinataire identiteDestinataireParDefaut() {
        return ActeurCourant::utilisateurId;
    }
}
