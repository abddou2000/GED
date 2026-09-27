package com.ipt.ged.depot;

import com.ipt.ged.fichier.ErreurFichierException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Refus du dépôt avec métadonnées (§5.3, §12.11 temps 1), avec code stable.
 *
 * <p>Hérite d'{@link ErreurFichierException}, donc d'{@code ExceptionMetier} :
 * le gestionnaire commun la rend avec son statut et son code (problem+json
 * après fusion du lot erreurs), sans gestionnaire propre au lot.
 */
public class ErreurDepot extends ErreurFichierException {

    /** HTTP 400 — métadonnées refusées par le plan d'indexation (dictionnaire {@code erreurs} par champ). */
    public static final String METADONNEES_INVALIDES = "METADONNEES_INVALIDES";
    /** HTTP 413 — métadonnées au-delà de 64 Ko (§5.3.2). */
    public static final String METADONNEES_TROP_VOLUMINEUSES = "METADONNEES_TROP_VOLUMINEUSES";

    public ErreurDepot(HttpStatus statut, String code, String message) {
        super(statut, code, message);
    }

    /**
     * Même refus que la modification des métadonnées (lot modèle) : un seul
     * type d'erreur pour « métadonnées refusées par le plan », rendu par le
     * gestionnaire commun des {@code ExceptionMetier} avec le dictionnaire
     * {@code erreurs} (le gestionnaire des erreurs de fichier, lui, ne porte
     * pas les membres d'extension).
     */
    static com.ipt.ged.planindexation.metamodele.MetadonneesInvalidesException invalides(Map<String, String> erreurs) {
        return new com.ipt.ged.planindexation.metamodele.MetadonneesInvalidesException(erreurs);
    }

    static ErreurDepot tropVolumineuses(int limite) {
        return new ErreurDepot(HttpStatus.PAYLOAD_TOO_LARGE, METADONNEES_TROP_VOLUMINEUSES,
                "Métadonnées trop volumineuses : " + (limite / 1024) + " Ko au plus par requête.");
    }
}
