package com.ipt.ged.journalisation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

import java.util.List;

/**
 * Câblage de la journalisation technique de l'Article 50 (DAT 7.1, 7.3).
 *
 * <p>Les deux filtres encadrent la chaîne Spring Security, dont l'ordre est
 * {@link SecurityProperties#DEFAULT_FILTER_ORDER} : le premier avant (adresse
 * et trace connues dès l'authentification), le second après (identité
 * établie).
 */
@Configuration
public class ConfigurationJournalisation {

    @Bean
    public FilterRegistrationBean<FiltreContexteRequete> filtreContexteRequete(
            @Value("${ged.journalisation.proxys-de-confiance:127.0.0.1,::1}") List<String> proxys) {
        var enregistrement = new FilterRegistrationBean<>(new FiltreContexteRequete(proxys));
        enregistrement.setOrder(Ordered.HIGHEST_PRECEDENCE);
        enregistrement.setName("filtreContexteRequete");
        return enregistrement;
    }

    @Bean
    public FilterRegistrationBean<FiltreUtilisateurJournalisation> filtreUtilisateurJournalisation() {
        var enregistrement = new FilterRegistrationBean<>(new FiltreUtilisateurJournalisation());
        enregistrement.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        enregistrement.setName("filtreUtilisateurJournalisation");
        return enregistrement;
    }

    @Bean
    public TaskDecorator decorateurTacheMdc() {
        return new DecorateurTacheMdc();
    }
}
