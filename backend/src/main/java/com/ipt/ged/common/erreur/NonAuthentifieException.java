package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 401 — appelant non authentifié : jeton ou clé absent, invalide ou expiré.
 */
public class NonAuthentifieException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#NON_AUTHENTIFIE}. */
    public NonAuthentifieException(String detail) {
        super(HttpStatus.UNAUTHORIZED, CodesErreur.NON_AUTHENTIFIE, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public NonAuthentifieException(String code, String detail) {
        super(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public NonAuthentifieException(String code, String detail, Throwable cause) {
        super(HttpStatus.UNAUTHORIZED, code, detail, cause);
    }
}
