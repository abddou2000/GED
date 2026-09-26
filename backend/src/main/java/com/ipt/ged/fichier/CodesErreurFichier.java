package com.ipt.ged.fichier;

/**
 * Codes d'erreur renvoyés dans le champ {@code code} des réponses d'erreur.
 *
 * <p>Réunis ici pour que le frontend et les applications tierces aient une
 * seule liste à connaître ; {@link #FICHIER_INFECTE} est imposé tel quel par le
 * dossier technique (§6.1.5).
 */
public final class CodesErreurFichier {

    private CodesErreurFichier() {}

    /** HTTP 413 — taille du type documentaire ou plafond de plateforme dépassé. */
    public static final String FICHIER_TROP_VOLUMINEUX = "FICHIER_TROP_VOLUMINEUX";
    /** HTTP 415 — type réel (détecté par le contenu) hors liste blanche. */
    public static final String FORMAT_NON_AUTORISE = "FORMAT_NON_AUTORISE";
    /** HTTP 422 — l'antivirus a reconnu une signature. */
    public static final String FICHIER_INFECTE = "FICHIER_INFECTE";
    /** HTTP 503 — antivirus injoignable ou en erreur : dépôt refusé (échec fermé). */
    public static final String ANTIVIRUS_INDISPONIBLE = "ANTIVIRUS_INDISPONIBLE";
    /** HTTP 404 — fichier ou clé de fichier absent (purgé, jamais écrit). */
    public static final String FICHIER_INTROUVABLE = "FICHIER_INTROUVABLE";
    /** HTTP 500 — authentification GCM en échec : fichier altéré ou tronqué. */
    public static final String INTEGRITE_COMPROMISE = "INTEGRITE_COMPROMISE";
    /** HTTP 415 — format valide mais sans prévisualisation possible. */
    public static final String APERCU_NON_DISPONIBLE = "APERCU_NON_DISPONIBLE";
    /** HTTP 503 — conversion bureautique impossible (LibreOffice absent ou en échec). */
    public static final String CONVERSION_INDISPONIBLE = "CONVERSION_INDISPONIBLE";
}
