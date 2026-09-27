package com.ipt.ged.notification;

/** État de l'expédition par e-mail d'une notification (contrainte {@code ck_notification_courriel_etat}). */
public enum EtatCourriel {
    /** En attente d'expédition ou d'une nouvelle tentative. */
    A_ENVOYER,
    /** Accepté par le relais SMTP. */
    ENVOYE,
    /** Tentatives épuisées : la notification reste visible dans l'application. */
    ECHEC,
    /** L'utilisateur a désactivé l'e-mail : seule la notification in-app existe. */
    DESACTIVE,
    /** Aucune adresse connue pour le destinataire dans l'annuaire. */
    SANS_ADRESSE
}
