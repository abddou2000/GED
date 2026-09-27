package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/**
 * 401 {@code IDENTIFIANTS_REFUSES} : identifiant inconnu, mot de passe faux ou
 * compte désactivé dans l'annuaire. Un seul message pour les trois, sans quoi la
 * différence permettrait d'énumérer les comptes.
 */
public class IdentifiantsRefusesException extends ErreurIdentite {
    public IdentifiantsRefusesException() {
        super(HttpStatus.UNAUTHORIZED, "IDENTIFIANTS_REFUSES", "Identifiant ou mot de passe incorrect.");
    }
}
