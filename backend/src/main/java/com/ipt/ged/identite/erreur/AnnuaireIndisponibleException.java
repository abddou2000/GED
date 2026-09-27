package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/**
 * 503 {@code ANNUAIRE_INDISPONIBLE} : aucun contrôleur de domaine ne répond.
 * Message explicite et aucun mode dégradé (dossier technique §3.3) ; les sessions
 * déjà ouvertes continuent jusqu'à leur expiration.
 */
public class AnnuaireIndisponibleException extends ErreurIdentite {
    public AnnuaireIndisponibleException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "ANNUAIRE_INDISPONIBLE",
                "L'annuaire de l'entreprise ne répond pas : connexion impossible pour le moment. "
                        + "Les sessions déjà ouvertes ne sont pas affectées.");
        initCause(cause);
    }
}
