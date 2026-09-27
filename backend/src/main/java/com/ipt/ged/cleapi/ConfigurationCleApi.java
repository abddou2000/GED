package com.ipt.ged.cleapi;

import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.common.erreur.RegleMetierException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Câblage des clés d'API et implémentations par défaut des points
 * d'extension, remplacées par les lots suivants en déclarant leur propre bean.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProprietesCleApi.class)
public class ConfigurationCleApi {

    /** Utilisateur authentifié, jamais une application. Remplacée par la permission d'E3. */
    @Bean
    @ConditionalOnMissingBean(GardeAdministrationCles.class)
    public GardeAdministrationCles gardeAdministrationClesParDefaut() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a instanceof ApplicationAuthentifiee) {
                throw new AccesRefuseException(CodesErreurCleApi.ADMINISTRATION_RESERVEE,
                        "L'administration des clés d'API est réservée aux administrateurs.");
            }
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
                throw new AccesRefuseException("Administration des clés d'API réservée à l'Administrateur.");
            }
        };
    }

    /**
     * Portée non encore évaluée : l'authentification de la clé fait seule foi,
     * comme les droits des utilisateurs avant le lot autorisation. Remplacée en
     * vague 4 par l'évaluation de {@code cle_api_portee} via le point
     * d'application unique.
     */
    @Bean
    @ConditionalOnMissingBean(ControlePorteeApplication.class)
    public ControlePorteeApplication controlePorteeProvisoire() {
        return (application, operation, noeudId) -> { };
    }

    /** Délégation non encore résolue : refusée (échec fermé, DAT §5.5). */
    @Bean
    @ConditionalOnMissingBean(ResolveurIdentiteDeleguee.class)
    public ResolveurIdentiteDeleguee resolveurDelegationFerme() {
        return (application, valeur) -> {
            throw new RegleMetierException(CodesErreurCleApi.IDENTITE_DELEGUEE_INVALIDE,
                    "Identité déléguée non vérifiable : la délégation n'est pas encore disponible.");
        };
    }
}
