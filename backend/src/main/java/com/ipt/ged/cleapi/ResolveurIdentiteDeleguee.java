package com.ipt.ged.cleapi;

import com.ipt.ged.security.UtilisateurConnecte;

import java.util.UUID;

/**
 * Point d'extension de la délégation d'identité (DAT §5.5, en-tête
 * {@code X-On-Behalf-Of}) : résout l'identifiant d'annuaire transmis par une
 * application habilitée à déléguer en une identité GED.
 *
 * <p>Implémentation livrée : {@link ResolveurDelegationAnnuaire} (identités et
 * cache d'annuaire du lot E2). Échec fermé : une identité non vérifiable fait
 * refuser la requête, jamais exécuter au nom de l'application seule.
 */
@FunctionalInterface
public interface ResolveurIdentiteDeleguee {

    /** Identité déléguée résolue : identité GED, identifiant d'annuaire et principal de la requête. */
    record IdentiteDeleguee(UUID utilisateurId, String identifiant, UtilisateurConnecte principal) {}

    /**
     * @param valeur contenu de {@code X-On-Behalf-Of} ({@code sAMAccountName} ou objectGUID)
     * @throws com.ipt.ged.common.erreur.ExceptionMetier 422 {@code IDENTITE_DELEGUEE_INVALIDE}
     *         si l'identité est invalide ou inconnue de l'annuaire
     */
    IdentiteDeleguee resoudre(ApplicationAuthentifiee application, String valeur);
}
