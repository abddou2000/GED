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

    static ErreurDepot invalides(Map<String, String> erreurs) {
        StringBuilder detail = new StringBuilder("Métadonnées refusées par le plan d'indexation :");
        erreurs.forEach((champ, motif) -> detail.append(' ').append(champ).append(' ').append(motif));
        ErreurDepot e = new ErreurDepot(HttpStatus.BAD_REQUEST, METADONNEES_INVALIDES, detail.toString());
        e.avec("erreurs", Map.copyOf(erreurs));
        return e;
    }

    static ErreurDepot tropVolumineuses(int limite) {
        return new ErreurDepot(HttpStatus.PAYLOAD_TOO_LARGE, METADONNEES_TROP_VOLUMINEUSES,
                "Métadonnées trop volumineuses : " + (limite / 1024) + " Ko au plus par requête.");
    }
}
