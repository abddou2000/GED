package com.ipt.ged.identite.session;

/** Raison de la fin d'une session (contrainte {@code ck_session_motif_revocation}). */
public enum MotifRevocation {
    /** L'utilisateur s'est déconnecté. */
    DECONNEXION,
    /** L'Administrateur a révoqué les sessions de l'utilisateur (risque R26). */
    REVOCATION_ADMINISTRATEUR,
    /** Un jeton déjà consommé a été présenté : vol présumé, toute la famille tombe. */
    REUTILISATION,
    /** Durée absolue dépassée. */
    EXPIRATION,
    /** Inactivité maximale dépassée. */
    INACTIVITE
}
