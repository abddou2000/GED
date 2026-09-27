package com.ipt.ged.depot.source;

/**
 * Canal par lequel un document est entré dans la GED (T-040, dossier technique
 * §5.1 et §12.11 temps 1, dossier fonctionnel §4.1.3), porté par
 * {@code document.canal_depot}.
 */
public enum CanalDepot {
    /** Dépôt par l'interface web, par un utilisateur authentifié. */
    INTERFACE,
    /** Dépôt par une application cliente identifiée par sa clé d'API (§5.2). */
    API,
    /**
     * Dépôt par le bureau d'ordre digital : une application cliente parmi
     * d'autres (§5.2), distinguée par son code d'application.
     */
    BUREAU_ORDRE,
    /** Document repris de l'application d'origine, ou antérieur à l'enregistrement du canal. */
    REPRISE
}
