package com.ipt.ged.cleapi;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.common.erreur.NonAuthentifieException;
import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.identite.annuaire.Annuaire;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Câblage des clés d'API et implémentations des points d'extension sur les lots
 * identité (E2) et autorisation (E3). Chacune reste remplaçable en déclarant son
 * propre bean.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ProprietesCleApi.class, ProprietesDelegation.class})
public class ConfigurationCleApi {

    /**
     * Administration des applications et des clés : permission
     * {@code GERER_CLES_API} (rôle Administrateur), jamais une application.
     */
    @Bean
    @ConditionalOnMissingBean(GardeAdministrationCles.class)
    public GardeAdministrationCles gardeAdministrationCles(ControleAcces controle) {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a instanceof ApplicationAuthentifiee) {
                throw new AccesRefuseException(CodesErreurCleApi.ADMINISTRATION_RESERVEE,
                        "L'administration des clés d'API est réservée aux administrateurs.");
            }
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
                throw new NonAuthentifieException("Authentification requise.");
            }
            controle.exigerAdministration(CodePermission.GERER_CLES_API);
        };
    }

    /**
     * Portée d'une clé : même décision que pour un utilisateur (point
     * d'application unique), la clé étant un sujet
     * ({@link SourceHabilitationsApplications}). Toutes les permissions de
     * l'opération sont exigées sur le nœud : 404 hors portée, 403 sinon.
     */
    @Bean
    @ConditionalOnMissingBean(ControlePorteeApplication.class)
    public ControlePorteeApplication controlePorteeParDroits(ControleAcces controle) {
        return (application, operation, noeudId) -> {
            for (CodePermission p : operation.permissions()) controle.exigerSurNoeud(p, noeudId);
        };
    }

    /** Délégation résolue par les identités GED et l'annuaire (lot E2). */
    @Bean
    @ConditionalOnMissingBean(ResolveurIdentiteDeleguee.class)
    public ResolveurIdentiteDeleguee resolveurDelegationAnnuaire(UtilisateurRepository utilisateurs,
                                                                 ServiceIdentites identites, Annuaire annuaire,
                                                                 ApplicationRepository applications,
                                                                 ProprietesDelegation proprietes) {
        return new ResolveurDelegationAnnuaire(utilisateurs, identites, annuaire, applications, proprietes);
    }
}
