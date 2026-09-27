package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 400 — requête mal formée ou incohérente.
 */
public class RequeteInvalideException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#REQUETE_INVALIDE}. */
    public RequeteInvalideException(String detail) {
        super(HttpStatus.BAD_REQUEST, CodesErreur.REQUETE_INVALIDE, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public RequeteInvalideException(String code, String detail) {
        super(HttpStatus.BAD_REQUEST, code, detail);
    }

    public RequeteInvalideException(String code, String detail, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, code, detail, cause);
    }
}
