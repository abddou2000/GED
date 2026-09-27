package com.ipt.ged.idempotence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Réglages de l'idempotence des créations (DAT §5.3.2), sous
 * {@code ged.api.idempotence}.
 *
 * @param routes    créations soumises à {@code Idempotency-Key} : « MÉTHODE motif »,
 *                  motif au format des chemins Spring ({@code *} = un segment)
 * @param duree     durée de mémorisation de la réponse (24 h)
 * @param corpsMax  taille maximale d'une réponse mémorisée ; au-delà, seuls le
 *                  statut et l'en-tête Location sont rejoués
 */
@ConfigurationProperties("ged.api.idempotence")
public record ProprietesIdempotence(List<String> routes, Duration duree, Integer corpsMax) {

    public ProprietesIdempotence {
        routes = routes == null || routes.isEmpty() ? List.of(
                "POST /api/v1/documents",
                "POST /api/v1/documents/*/versions",
                "POST /api/v1/documents/*/rattachements",
                "POST /api/v1/workspaces",
                "POST /api/v1/noeuds/*/dossiers") : List.copyOf(routes);
        duree = duree == null ? Duration.ofHours(24) : duree;
        corpsMax = corpsMax == null ? 1024 * 1024 : corpsMax;
    }
}
