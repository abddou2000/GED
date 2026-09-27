package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 422 — requête bien formée mais contraire à une règle métier.
 */
public class RegleMetierException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#REGLE_METIER_VIOLEE}. */
    public RegleMetierException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, CodesErreur.REGLE_METIER_VIOLEE, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public RegleMetierException(String code, String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    public RegleMetierException(String code, String detail, Throwable cause) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, detail, cause);
    }
}
