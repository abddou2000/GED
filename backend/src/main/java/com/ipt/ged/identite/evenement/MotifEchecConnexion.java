package com.ipt.ged.identite.evenement;

/** Cause d'un refus de connexion, à code stable pour le journal d'audit. */
public enum MotifEchecConnexion {
    /** Identifiant inconnu, mot de passe faux ou compte désactivé dans l'annuaire (indistincts, D1). */
    IDENTIFIANTS_REFUSES,
    /** Limitation de débit atteinte (IP ou identifiant). */
    TROP_DE_TENTATIVES,
    /** Annuaire injoignable : aucune connexion possible, aucun mode dégradé. */
    ANNUAIRE_INDISPONIBLE
}
