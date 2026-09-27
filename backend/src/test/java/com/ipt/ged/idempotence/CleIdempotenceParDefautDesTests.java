package com.ipt.ged.idempotence;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import java.util.UUID;

/**
 * Dans les tests MockMvc, toute requête qui ne porte pas d'{@code Idempotency-Key}
 * en reçoit une neuve, comme le fait l'intercepteur Angular
 * ({@code core/idempotence.ts}) pour chaque création : les tests des modules
 * n'ont pas à s'en soucier, et chaque requête est une création distincte.
 *
 * <p>Les tests de l'idempotence posent leur propre clé (rejeu, conflit) ; ceux
 * qui vérifient l'obligation l'omettent avec l'en-tête vide
 * {@link #SANS_CLE}.
 */
@Component
public class CleIdempotenceParDefautDesTests implements MockMvcBuilderCustomizer {

    /** Valeur à poser pour demander explicitement l'absence de clé. */
    public static final String SANS_CLE = "__sans-cle__";

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(MockMvcRequestBuilders.get("/").with(requete -> {
            String valeur = requete.getHeader(FiltreIdempotence.ENTETE);
            if (valeur == null) {
                requete.addHeader(FiltreIdempotence.ENTETE, UUID.randomUUID().toString());
            } else if (SANS_CLE.equals(valeur)) {
                requete.removeHeader(FiltreIdempotence.ENTETE);
            }
            return requete;
        }));
    }
}
