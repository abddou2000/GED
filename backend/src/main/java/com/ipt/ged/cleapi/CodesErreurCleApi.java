package com.ipt.ged.cleapi;

/**
 * Codes métier stables des refus liés aux clés d'API et aux applications
 * (champ {@code code} des réponses problem+json, DAT §5.3.2).
 */
public final class CodesErreurCleApi {

    private CodesErreurCleApi() {}

    /** 401 — clé absente du registre, mal formée ou secret faux. */
    public static final String CLE_API_INVALIDE = "CLE_API_INVALIDE";
    /** 401 — clé expirée (validité de 12 mois par défaut). */
    public static final String CLE_API_EXPIREE = "CLE_API_EXPIREE";
    /** 401 — clé révoquée par l'Administrateur. */
    public static final String CLE_API_REVOQUEE = "CLE_API_REVOQUEE";
    /** 401 — clé émise pour un autre environnement (UAT utilisée en PROD…). */
    public static final String CLE_API_AUTRE_ENVIRONNEMENT = "CLE_API_AUTRE_ENVIRONNEMENT";
    /** 403 — application désactivée. */
    public static final String APPLICATION_DESACTIVEE = "APPLICATION_DESACTIVEE";
    /** 403 — adresse source hors de la liste autorisée de l'application. */
    public static final String ADRESSE_NON_AUTORISEE = "ADRESSE_NON_AUTORISEE";
    /** 429 — quota par minute dépassé. */
    public static final String QUOTA_MINUTE_DEPASSE = "QUOTA_MINUTE_DEPASSE";
    /** 429 — quota journalier dépassé. */
    public static final String QUOTA_JOUR_DEPASSE = "QUOTA_JOUR_DEPASSE";
    /** 403 — en-tête X-On-Behalf-Of sans l'attribut « délégation » (§5.5). */
    public static final String DELEGATION_NON_AUTORISEE = "DELEGATION_NON_AUTORISEE";
    /** 422 — identité déléguée invalide, inconnue ou désactivée (§5.5). */
    public static final String IDENTITE_DELEGUEE_INVALIDE = "IDENTITE_DELEGUEE_INVALIDE";
    /** 403 — opération d'administration des clés demandée par une application. */
    public static final String ADMINISTRATION_RESERVEE = "ADMINISTRATION_RESERVEE";
    /** 409 — code d'application déjà utilisé. */
    public static final String APPLICATION_EXISTANTE = "APPLICATION_EXISTANTE";
    /** 422 — une clé révoquée ou remplacée ne se régénère pas. */
    public static final String CLE_API_NON_REGENERABLE = "CLE_API_NON_REGENERABLE";
    /** 422 — délégation demandée sans liste d'adresses autorisées (§5.4). */
    public static final String DELEGATION_SANS_ADRESSES = "DELEGATION_SANS_ADRESSES";
}
