package com.ipt.ged.documentationapi;

import com.ipt.ged.conventionsapi.ProprietesConventionsApi;
import com.ipt.ged.idempotence.ProprietesIdempotence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spécification OpenAPI 3 complète (T-053, DAT §5.3) : un
 * {@code OpenApiCustomizer} appliqué à la génération de springdoc, plutôt que
 * des annotations dans les contrôleurs des autres lots (aucun conflit de
 * fusion). La documentation reste fermée en production : routes refusées par
 * {@code SecurityConfig} et springdoc désactivé par le profil prod (api.yml).
 */
@Configuration
public class ConfigurationDocumentationApi {

    @Bean
    public DictionnaireDocumentation dictionnaireDocumentation() {
        return new DictionnaireDocumentation();
    }

    @Bean
    public EnrichissementOpenApi enrichissementOpenApi(DictionnaireDocumentation dictionnaire,
                                                       ProprietesIdempotence idempotence,
                                                       ProprietesConventionsApi conventions) {
        return new EnrichissementOpenApi(dictionnaire, idempotence, conventions);
    }
}
