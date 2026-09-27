package com.ipt.ged.conventionsapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * Conventions communes de l'API (DAT §5.3.2), sous {@code ged.api.conventions}.
 *
 * @param metadonneesMax taille maximale des métadonnées d'une requête : corps
 *                       JSON, ou champs non fichiers d'un envoi multipart (64 Ko)
 * @param depreciations  versions ou points d'entrée annoncés comme obsolètes :
 *                       leurs réponses portent {@code Deprecation} et {@code Sunset}
 */
@ConfigurationProperties("ged.api.conventions")
public record ProprietesConventionsApi(Integer metadonneesMax, List<Depreciation> depreciations) {

    public ProprietesConventionsApi {
        metadonneesMax = metadonneesMax == null ? 64 * 1024 : metadonneesMax;
        depreciations = depreciations == null ? List.of() : List.copyOf(depreciations);
    }

    /**
     * Annonce de dépréciation (DAT §5.3.2 : une rupture crée {@code /api/v2},
     * l'ancienne version restant supportée au moins 12 mois après l'annonce).
     *
     * @param prefixe     chemins concernés, par exemple {@code /api/v1/}
     * @param annonce     date d'annonce (en-tête {@code Deprecation})
     * @param retrait     date de retrait (en-tête {@code Sunset}), au moins 12 mois après l'annonce
     * @param successeur  URI de la version qui remplace (lien {@code successor-version}), facultatif
     */
    public record Depreciation(String prefixe, LocalDate annonce, LocalDate retrait, String successeur) {
        public Depreciation {
            if (prefixe == null || !prefixe.startsWith("/api/")) {
                throw new IllegalArgumentException("Dépréciation : préfixe /api/… attendu, reçu " + prefixe);
            }
            if (annonce == null || retrait == null || retrait.isBefore(annonce.plusMonths(12))) {
                throw new IllegalArgumentException("Dépréciation de " + prefixe
                        + " : retrait au moins 12 mois après l'annonce (DAT §5.3.2)");
            }
        }
    }
}
