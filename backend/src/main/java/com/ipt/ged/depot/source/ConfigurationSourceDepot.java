package com.ipt.ged.depot.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Source du dépôt (T-040) : implémentation par défaut, remplaçable par le lot intégration. */
@Configuration
public class ConfigurationSourceDepot {

    @Bean
    @ConditionalOnMissingBean(SourceDepot.class)
    public SourceDepot sourceDepot() {
        return new SourceDepotParDefaut();
    }
}
