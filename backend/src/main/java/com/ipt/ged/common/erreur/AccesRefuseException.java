package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 403 — appelant authentifié sans le droit requis.
 *
 * <p>Pour un objet hors du périmètre de l'appelant, lever
 * {@link RessourceIntrouvableException} : un objet non visible n'est jamais
 * distingué d'un objet absent (DAT 5.3.2).
 */
public class AccesRefuseException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#ACCES_REFUSE}. */
    public AccesRefuseException(String detail) {
        super(HttpStatus.FORBIDDEN, CodesErreur.ACCES_REFUSE, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public AccesRefuseException(String code, String detail) {
        super(HttpStatus.FORBIDDEN, code, detail);
    }

    public AccesRefuseException(String code, String detail, Throwable cause) {
        super(HttpStatus.FORBIDDEN, code, detail, cause);
    }
}
