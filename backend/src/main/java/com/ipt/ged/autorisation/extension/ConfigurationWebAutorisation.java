package com.ipt.ged.autorisation.extension;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enregistre {@link GardeDroitsRequetes} sur l'API. */
@Configuration
public class ConfigurationWebAutorisation implements WebMvcConfigurer {

    private final GardeDroitsRequetes garde;

    public ConfigurationWebAutorisation(GardeDroitsRequetes garde) {
        this.garde = garde;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(garde).addPathPatterns("/api/v1/**");
    }
}
