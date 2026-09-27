package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Objects;

/**
 * 429 — quota ou limitation de débit dépassé (anti-force brute, quotas des
 * clés d'API). Le gestionnaire pose l'en-tête {@code Retry-After}, en secondes
 * entières, exigé par le DAT 5.3.2.
 */
public class TropDeRequetesException extends ExceptionMetier {

    private final Duration reessayerApres;

    public TropDeRequetesException(String detail, Duration reessayerApres) {
        this(CodesErreur.TROP_DE_REQUETES, detail, reessayerApres);
    }

    public TropDeRequetesException(String code, String detail, Duration reessayerApres) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, detail);
        this.reessayerApres = Objects.requireNonNull(reessayerApres, "reessayerApres");
    }

    public Duration reessayerApres() {
        return reessayerApres;
    }

    /** Valeur de {@code Retry-After} : secondes arrondies au supérieur, au moins 1. */
    public long secondesAvantReessai() {
        long s = reessayerApres.toSeconds() + (reessayerApres.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, s);
    }
}
