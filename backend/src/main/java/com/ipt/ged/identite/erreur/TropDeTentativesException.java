package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/** 429 {@code TROP_DE_TENTATIVES}, avec l'en-tête {@code Retry-After} en secondes. */
public class TropDeTentativesException extends ErreurIdentite {

    private final long reessayerDansSecondes;

    public TropDeTentativesException(long reessayerDansSecondes) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TROP_DE_TENTATIVES",
                "Trop de tentatives de connexion. Réessayez dans " + reessayerDansSecondes + " seconde(s).");
        this.reessayerDansSecondes = reessayerDansSecondes;
    }

    public long getReessayerDansSecondes() { return reessayerDansSecondes; }
}
