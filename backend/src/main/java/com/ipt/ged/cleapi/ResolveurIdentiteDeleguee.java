package com.ipt.ged.cleapi;

import java.util.UUID;

/**
 * Point d'extension de la délégation d'identité (DAT §5.5, en-tête
 * {@code X-On-Behalf-Of}) : résout l'identifiant d'annuaire transmis par une
 * application habilitée à déléguer en une identité GED existante ou
 * provisionnée sans rôle.
 *
 * <p>Implémentation réelle en vague 4, sur le cache d'annuaire du lot
 * identité. En attendant, {@link ConfigurationCleApi} déclare un résolveur qui
 * refuse toute délégation ({@code IDENTITE_DELEGUEE_INVALIDE}) : l'opération
 * n'est jamais exécutée au nom d'une identité non vérifiée (échec fermé).
 */
@FunctionalInterface
public interface ResolveurIdentiteDeleguee {

    /** Identité déléguée résolue : identifiant GED et identifiant lisible. */
    record IdentiteDeleguee(UUID utilisateurId, String identifiant) {}

    /**
     * @param valeur contenu de {@code X-On-Behalf-Of} (login ou objectGUID)
     * @throws com.ipt.ged.common.erreur.ExceptionMetier 422 {@code IDENTITE_DELEGUEE_INVALIDE}
     *         si l'identité est invalide, inconnue ou désactivée
     */
    IdentiteDeleguee resoudre(ApplicationAuthentifiee application, String valeur);
}
