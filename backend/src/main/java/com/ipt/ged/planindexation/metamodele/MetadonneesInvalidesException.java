package com.ipt.ged.planindexation.metamodele;

import java.util.Map;

/**
 * Métadonnées refusées par le plan (§12.7) : 400 avec un dictionnaire
 * d'erreurs par champ ({@code erreurs}), code {@code METADONNEES_INVALIDES}.
 *
 * <p>Hérite d'{@link IllegalArgumentException} pour emprunter la traduction
 * 400 actuelle ; le conseil {@code GestionErreursMetamodele} ajoute le
 * dictionnaire. À la fusion du contrat problem+json de dev2 : sous-classe de
 * {@code RequeteInvalideException} avec la propriété {@code erreurs}.
 */
public class MetadonneesInvalidesException extends IllegalArgumentException {

    public static final String CODE = "METADONNEES_INVALIDES";

    private final Map<String, String> erreurs;

    public MetadonneesInvalidesException(Map<String, String> erreurs) {
        super("Métadonnées refusées par le plan d'indexation : " + String.join(", ", erreurs.keySet()));
        this.erreurs = Map.copyOf(erreurs);
    }

    public Map<String, String> erreurs() {
        return erreurs;
    }
}
