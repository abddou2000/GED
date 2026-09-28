package com.ipt.ged.identite.erreur;

import com.ipt.ged.common.erreur.TropDeRequetesException;

import java.time.Duration;

/**
 * 429 {@code TROP_DE_TENTATIVES}, avec l'en-tête {@code Retry-After} en secondes
 * (posé par le gestionnaire commun pour toute {@link TropDeRequetesException}).
 */
public class TropDeTentativesException extends TropDeRequetesException {

    public static final String CODE = "TROP_DE_TENTATIVES";

    public TropDeTentativesException(long reessayerDansSecondes) {
        super(CODE, "Trop de tentatives de connexion. Réessayez dans " + reessayerDansSecondes + " seconde(s).",
                Duration.ofSeconds(reessayerDansSecondes));
    }
}
