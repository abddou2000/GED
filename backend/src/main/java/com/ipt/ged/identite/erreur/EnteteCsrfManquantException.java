package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/**
 * 403 {@code ENTETE_CSRF_MANQUANT} : un point d'entrée fondé sur le cookie de
 * renouvellement a été appelé sans l'en-tête personnalisé exigé (dossier
 * technique §3.4.1, P-03). Un formulaire d'un site tiers ne peut pas le poser.
 */
public class EnteteCsrfManquantException extends ErreurIdentite {
    public EnteteCsrfManquantException(String entete) {
        super(HttpStatus.FORBIDDEN, "ENTETE_CSRF_MANQUANT", "En-tête " + entete + " obligatoire.");
    }
}
