package com.ipt.ged.conventionsapi;

import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage des conventions communes de l'API (DAT §5.3.2). */
@Configuration
@EnableConfigurationProperties(ProprietesConventionsApi.class)
public class ConfigurationConventionsApi {

    /** Après la sécurité (+0) et le filtre MDC de l'utilisateur (+1), avant l'idempotence (+3). */
    public static final int ORDRE_FILTRE = SecurityProperties.DEFAULT_FILTER_ORDER + 2;

    @Bean
    public FilterRegistrationBean<FiltreConventionsApi> filtreConventionsApi(ProprietesConventionsApi proprietes,
                                                                             ReponsesSecuriteProblem reponses) {
        var enregistrement = new FilterRegistrationBean<>(new FiltreConventionsApi(proprietes, reponses));
        enregistrement.setOrder(ORDRE_FILTRE);
        enregistrement.setName("filtreConventionsApi");
        return enregistrement;
    }
}
