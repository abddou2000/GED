package com.ipt.ged.identite;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Active le sous-arbre de configuration {@code ged.identite}. */
@Configuration
@EnableConfigurationProperties(ProprietesIdentite.class)
public class ConfigurationIdentite {
}
