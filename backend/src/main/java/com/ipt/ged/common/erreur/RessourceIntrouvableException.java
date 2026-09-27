package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

/**
 * 404 — objet inexistant <b>ou hors du périmètre</b> de l'appelant (DAT 5.3.2,
 * principe P5). Les deux cas produisent la même réponse : le {@code detail} est
 * un libellé fixe, pour qu'une différence de formulation entre deux lanceurs ne
 * trahisse jamais l'existence d'un objet.
 */
public class RessourceIntrouvableException extends ExceptionMetier {

    /** Libellé unique de toutes les réponses 404. */
    public static final String DETAIL = "La ressource demandée est introuvable.";

    public RessourceIntrouvableException() {
        super(HttpStatus.NOT_FOUND, CodesErreur.RESSOURCE_INTROUVABLE, DETAIL);
    }

    /** Code propre au domaine (par exemple {@code FICHIER_INTROUVABLE}). */
    public RessourceIntrouvableException(String code) {
        super(HttpStatus.NOT_FOUND, code, DETAIL);
    }
}
