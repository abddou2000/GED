package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/**
 * 401 {@code SESSION_EXPIREE} : jeton de renouvellement absent, inconnu, expiré,
 * révoqué ou déjà consommé. Le client doit se reconnecter.
 */
public class RenouvellementRefuseException extends ErreurIdentite {
    public RenouvellementRefuseException() {
        super(HttpStatus.UNAUTHORIZED, "SESSION_EXPIREE", "Session expirée : reconnectez-vous.");
    }
}
