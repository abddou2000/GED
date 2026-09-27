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

    /** Transitoire : employé et compte de connexion de cette branche (voir la classe). */
    @Bean
    @ConditionalOnMissingBean(AnnuaireDestinataires.class)
    public AnnuaireDestinataires annuaireDestinatairesLocal(JdbcTemplate jdbc) {
        return new AnnuaireDestinatairesLocal(jdbc);
    }

    /** Identité du jeton ; à la fusion E2 : {@code ActeurCourant::utilisateurId}. */
    @Bean
    @ConditionalOnMissingBean(IdentiteDestinataire.class)
    public IdentiteDestinataire identiteDestinataireParDefaut() {
        return ActeurCourant::employeId;
    }
}
