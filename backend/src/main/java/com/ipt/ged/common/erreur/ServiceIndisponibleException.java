package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 503 — dépendance indisponible (annuaire, antivirus, conversion…) : l'appelant
 * peut réessayer.
 */
public class ServiceIndisponibleException extends ExceptionMetier {

    /** Avec le code générique {@link CodesErreur#SERVICE_INDISPONIBLE}. */
    public ServiceIndisponibleException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, CodesErreur.SERVICE_INDISPONIBLE, detail);
    }

    /** Avec un code propre au domaine (constante du catalogue du domaine). */
    public ServiceIndisponibleException(String code, String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, detail);
    }

    public ServiceIndisponibleException(String code, String detail, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, detail, cause);
    }
}
