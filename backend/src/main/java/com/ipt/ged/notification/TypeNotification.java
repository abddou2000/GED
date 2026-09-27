package com.ipt.ged.notification;

/**
 * Cas de notification autorisés (DAT §12.9, dossier fonctionnel §4.6.6) :
 * <b>trois familles exclusivement</b>. Aucun autre événement ne notifie, et la
 * contrainte {@code ck_notification_type} le garantit aussi en base.
 *
 * <p>Ajouter un cas demande une décision du client (hors périmètre du DAT) :
 * on ne l'ajoute ni ici ni dans la contrainte sans elle.
 */
public enum TypeNotification {

    /** Circuit de validation ouvert : les validateurs sont prévenus. */
    CIRCUIT_OUVERT(Famille.CIRCUIT_VALIDATION),
    /** Décision rendue (validée, refusée ou annulée par le validateur) : l'initiateur est prévenu. */
    CIRCUIT_DECISION(Famille.CIRCUIT_VALIDATION),
    /** Circuit annulé : les validateurs sont prévenus (§12.8). */
    CIRCUIT_ANNULE(Famille.CIRCUIT_VALIDATION),
    /** Accès à un espace attribué — l'attribution seule, jamais le retrait. */
    ACCES_ESPACE_ATTRIBUE(Famille.ACCES_ESPACE),
    /** Échéance de conservation atteinte : les Agents d'archive sont prévenus. */
    ECHEANCE_CONSERVATION(Famille.FIN_CONSERVATION);

    public enum Famille { CIRCUIT_VALIDATION, ACCES_ESPACE, FIN_CONSERVATION }

    private final Famille famille;

    TypeNotification(Famille famille) {
        this.famille = famille;
    }

    public Famille famille() {
        return famille;
    }
}
