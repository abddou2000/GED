package com.ipt.ged.fichier;

import org.springframework.http.HttpStatus;

import static com.ipt.ged.fichier.CodesErreurFichier.*;

/**
 * Fabriques des erreurs de fichier, pour que le statut et le code restent
 * toujours appariés de la même façon (un 415 avec le code d'un 413 serait
 * indétectable côté client).
 */
public final class Refus {

    private Refus() {}

    public static ErreurFichierException tropVolumineux(long limiteOctets) {
        return new ErreurFichierException(HttpStatus.PAYLOAD_TOO_LARGE, FICHIER_TROP_VOLUMINEUX,
                "Fichier trop volumineux (max " + enMo(limiteOctets) + " Mo).");
    }

    public static ErreurFichierException formatNonAutorise(String typeReel) {
        return new ErreurFichierException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, FORMAT_NON_AUTORISE,
                "Format non autorisé pour ce type de document (type détecté : " + typeReel + ").");
    }

    public static ErreurFichierException infecte(String signature) {
        return new ErreurFichierException(HttpStatus.UNPROCESSABLE_ENTITY, FICHIER_INFECTE,
                "Fichier refusé : l'antivirus a détecté « " + signature + " ».");
    }

    /**
     * La raison technique (hôte, port, réponse brute) reste dans la cause et au
     * journal : le client n'a pas à apprendre la topologie de l'infrastructure.
     */
    public static ErreurFichierException antivirusIndisponible(Throwable cause) {
        return new ErreurFichierException(HttpStatus.SERVICE_UNAVAILABLE, ANTIVIRUS_INDISPONIBLE,
                "Analyse antivirus indisponible : dépôt refusé. Réessayez plus tard.", cause);
    }

    public static ErreurFichierException introuvable(String quoi) {
        return new ErreurFichierException(HttpStatus.NOT_FOUND, FICHIER_INTROUVABLE,
                "Fichier introuvable : " + quoi);
    }

    public static ErreurFichierException integriteCompromise(String detail, Throwable cause) {
        return new ErreurFichierException(HttpStatus.INTERNAL_SERVER_ERROR, INTEGRITE_COMPROMISE,
                "Le fichier stocké est altéré ou tronqué : lecture refusée (" + detail + ").", cause);
    }

    public static ErreurFichierException apercuNonDisponible(String typeMime) {
        return new ErreurFichierException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, APERCU_NON_DISPONIBLE,
                "Aucune prévisualisation possible pour ce format (" + typeMime + ") : téléchargez le fichier.");
    }

    public static ErreurFichierException conversionIndisponible(String raison, Throwable cause) {
        return new ErreurFichierException(HttpStatus.SERVICE_UNAVAILABLE, CONVERSION_INDISPONIBLE,
                "Prévisualisation bureautique indisponible : " + raison, cause);
    }

    private static long enMo(long octets) {
        return octets / (1024 * 1024);
    }
}
