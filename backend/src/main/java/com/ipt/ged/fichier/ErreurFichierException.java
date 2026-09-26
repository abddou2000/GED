package com.ipt.ged.fichier;

import org.springframework.http.HttpStatus;

/**
 * Refus ou panne liés à un fichier, portant son propre statut HTTP et un code
 * stable.
 *
 * <p>Le dossier technique impose des statuts précis (413, 415, 422 avec le code
 * {@code FICHIER_INFECTE}) que le gestionnaire commun ne sait pas déduire d'une
 * {@link IllegalArgumentException}, toujours rendue en 400. Porter le statut et
 * le code dans l'exception laisse un seul gestionnaire les traduire, sans qu'un
 * nouveau cas de refus exige d'y ajouter une méthode.
 */
public class ErreurFichierException extends RuntimeException {

    private final HttpStatus statut;
    private final String code;

    public ErreurFichierException(HttpStatus statut, String code, String message) {
        super(message);
        this.statut = statut;
        this.code = code;
    }

    public ErreurFichierException(HttpStatus statut, String code, String message, Throwable cause) {
        super(message, cause);
        this.statut = statut;
        this.code = code;
    }

    public HttpStatus statut() {
        return statut;
    }

    /** Code stable, lisible par un client (ne change pas avec le libellé). */
    public String code() {
        return code;
    }
}
