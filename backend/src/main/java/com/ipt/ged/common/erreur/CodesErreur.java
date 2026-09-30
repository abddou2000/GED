package com.ipt.ged.common.erreur;

/**
 * Catalogue des codes métier génériques du champ {@code code} des réponses
 * {@code application/problem+json} (DAT 5.3.2).
 *
 * <p>Un code est un <b>contrat</b> : les applications tierces et le front le
 * lisent pour décider d'une conduite, sans analyser un libellé destiné à
 * l'utilisateur. Il ne change jamais une fois publié ; un nouveau cas reçoit
 * un nouveau code. Les codes propres à un domaine sont déclarés par ce domaine
 * (par exemple {@code com.ipt.ged.fichier.CodesErreurFichier} :
 * {@code FICHIER_INFECTE}, {@code INTEGRITE_COMPROMISE}…) et suivent la même
 * forme : majuscules, chiffres et soulignés.
 */
public final class CodesErreur {

    private CodesErreur() {}

    /** 400 — corps, paramètre ou en-tête illisible ou incohérent. */
    public static final String REQUETE_INVALIDE = "REQUETE_INVALIDE";
    /** 400 — un ou plusieurs champs refusés ; détail dans le dictionnaire {@code erreurs}. */
    public static final String VALIDATION_ECHOUEE = "VALIDATION_ECHOUEE";
    /** 400 — identifiant ou paramètre de mauvais type (UUID attendu…). */
    public static final String PARAMETRE_INVALIDE = "PARAMETRE_INVALIDE";
    /**
     * 400 — paramètre de requête ou champ du corps inconnu de ce point d'entrée
     * (propriété {@code parametre}) : refusé plutôt qu'ignoré, pour qu'un critère
     * mal orthographié ne rende pas en silence un résultat non filtré.
     */
    public static final String PARAMETRE_INCONNU = "PARAMETRE_INCONNU";
    /** 400 — donnée refusée par une contrainte du schéma (longueur, unicité, obligation). */
    public static final String DONNEE_REFUSEE = "DONNEE_REFUSEE";
    /** 401 — appelant non authentifié : jeton ou clé absent, invalide ou expiré. */
    public static final String NON_AUTHENTIFIE = "NON_AUTHENTIFIE";
    /** 403 — appelant authentifié sans le droit requis. */
    public static final String ACCES_REFUSE = "ACCES_REFUSE";
    /**
     * 404 — objet inexistant <b>ou hors du périmètre</b> de l'appelant : les deux
     * cas produisent une réponse identique (DAT 5.3.2, principe P5).
     */
    public static final String RESSOURCE_INTROUVABLE = "RESSOURCE_INTROUVABLE";
    /** 405 — méthode HTTP non prise en charge par ce point d'entrée. */
    public static final String METHODE_NON_AUTORISEE = "METHODE_NON_AUTORISEE";
    /** 406 — aucun format de réponse acceptable pour l'appelant. */
    public static final String FORMAT_REPONSE_NON_ACCEPTABLE = "FORMAT_REPONSE_NON_ACCEPTABLE";
    /** 409 — conflit d'état : verrou, version obsolète. */
    public static final String CONFLIT = "CONFLIT";
    /** 409 — écriture concurrente sur la même ressource : la requête peut être rejouée. */
    public static final String MODIFICATION_CONCURRENTE = "MODIFICATION_CONCURRENTE";
    /** 413 — corps de requête au-delà du plafond de la plateforme. */
    public static final String REQUETE_TROP_VOLUMINEUSE = "REQUETE_TROP_VOLUMINEUSE";
    /** 415 — type de contenu de la requête non pris en charge. */
    public static final String TYPE_CONTENU_NON_SUPPORTE = "TYPE_CONTENU_NON_SUPPORTE";
    /** 422 — requête bien formée mais contraire à une règle métier. */
    public static final String REGLE_METIER_VIOLEE = "REGLE_METIER_VIOLEE";
    /** 429 — quota ou limitation de débit dépassé ; voir l'en-tête {@code Retry-After}. */
    public static final String TROP_DE_REQUETES = "TROP_DE_REQUETES";
    /** 500 — erreur non prévue ; le {@code traceId} la retrouve dans les journaux. */
    public static final String ERREUR_INTERNE = "ERREUR_INTERNE";
    /** 503 — dépendance indisponible (base, annuaire, antivirus…) ; réessayer plus tard. */
    public static final String SERVICE_INDISPONIBLE = "SERVICE_INDISPONIBLE";
}
