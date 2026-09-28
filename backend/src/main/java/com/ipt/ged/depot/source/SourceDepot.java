package com.ipt.ged.depot.source;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * <b>Point d'extension</b> : origine d'un dépôt, lue dans l'authentification de
 * la requête (T-040, §5.1, §5.5, §12.11 « horodatage, source et déposant sont
 * enregistrés » dès le temps 1).
 *
 * <p>{@link SourceDepotParDefaut} reconnaît l'utilisateur de l'interface. Le lot
 * intégration déclare la sienne ({@code cleapi.SourceDepotApplications},
 * {@code @Primary}) pour les applications : canal {@code API} ou
 * {@code BUREAU_ORDRE}, application appelante, et, en cas de délégation
 * ({@code X-On-Behalf-Of}), l'identité GED de la personne pour le compte de
 * laquelle l'application dépose.
 */
public interface SourceDepot {

    /**
     * @param canal                 canal du dépôt ;
     * @param applicationId         application appelante (clé d'API), {@code null} depuis l'interface ;
     * @param applicationCode       code lisible de l'application, {@code null} depuis l'interface ;
     * @param deposantUtilisateurId identité GED du déposant : l'utilisateur de l'interface, ou la
     *                              personne déléguée par l'application ; {@code null} pour une
     *                              application qui dépose en son nom propre ;
     * @param delegue               vrai si l'application dépose pour le compte d'une personne
     *                              ({@code X-On-Behalf-Of}, §5.5).
     */
    record Origine(CanalDepot canal, UUID applicationId, String applicationCode, UUID deposantUtilisateurId,
                   boolean delegue) {

        public static Origine interfaceWeb(UUID utilisateurId) {
            return new Origine(CanalDepot.INTERFACE, null, null, utilisateurId, false);
        }
    }

    Origine origine(Authentication authentification);
}
