package com.ipt.ged.planindexation.metamodele;

import java.util.Map;

/**
 * Métadonnées refusées par le plan (§12.7) : 400 {@code METADONNEES_INVALIDES}
 * avec le dictionnaire d'erreurs par champ (propriété {@code erreurs} du
 * problem+json, contrat de dev2).
 */
public class MetadonneesInvalidesException extends com.ipt.ged.common.erreur.RequeteInvalideException {

    public static final String CODE = "METADONNEES_INVALIDES";

    private final Map<String, String> erreurs;

    public MetadonneesInvalidesException(Map<String, String> erreurs) {
        super(CODE, "Métadonnées refusées par le plan d'indexation : " + String.join(", ", erreurs.keySet()));
        this.erreurs = Map.copyOf(erreurs);
        avec("erreurs", this.erreurs);
    }

    public Map<String, String> erreurs() {
        return erreurs;
    }
}
