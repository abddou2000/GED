package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 409 — conflit d'état : document verrouillé, version obsolète.
 */
public class ConflitException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#CONFLIT}. */
    public ConflitException(String detail) {
        super(HttpStatus.CONFLICT, CodesErreur.CONFLIT, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public ConflitException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }

    public ConflitException(String code, String detail, Throwable cause) {
        super(HttpStatus.CONFLICT, code, detail, cause);
    }
}
