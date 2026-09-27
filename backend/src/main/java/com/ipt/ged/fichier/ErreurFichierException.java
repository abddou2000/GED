package com.ipt.ged.fichier;

import com.ipt.ged.common.erreur.ExceptionMetier;
import org.springframework.http.HttpStatus;

/**
 * Refus ou panne liés à un fichier, avec son statut HTTP et un code stable
 * ({@code FICHIER_TROP_VOLUMINEUX} 413, {@code FORMAT_NON_AUTORISE} 415,
 * {@code FICHIER_INFECTE} 422, {@code ANTIVIRUS_INDISPONIBLE} 503,
 * {@code INTEGRITE_COMPROMISE} 500…).
 *
 * <p>Hérite d'{@link ExceptionMetier}, le contrat d'erreurs commun : le
 * gestionnaire commun la rend en {@code application/problem+json} avec son
 * code, sans qu'aucun gestionnaire propre au lot fichiers soit nécessaire.
 */
public class ErreurFichierException extends ExceptionMetier {

    public ErreurFichierException(HttpStatus statut, String code, String message) {
        super(statut, code, message);
    }

    public ErreurFichierException(HttpStatus statut, String code, String message, Throwable cause) {
        super(statut, code, message, cause);
    }
}
