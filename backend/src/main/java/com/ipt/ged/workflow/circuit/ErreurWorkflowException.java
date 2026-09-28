package com.ipt.ged.workflow.circuit;

import org.springframework.http.HttpStatus;

/**
 * Refus métier du workflow, rendu avec un code stable (contrat d'API E8, D8) :
 * les applications de l'intranet réagissent au code, pas au libellé. Une
 * {@link com.ipt.ged.common.erreur.ExceptionMetier} : le gestionnaire commun
 * (dev2) la rend en problem+json ({@code code}, {@code detail}).
 */
public class ErreurWorkflowException extends com.ipt.ged.common.erreur.ExceptionMetier {

    public static final String MOTIF_OBLIGATOIRE = "MOTIF_OBLIGATOIRE";
    public static final String PAS_VALIDATEUR = "PAS_VALIDATEUR";
    public static final String CIRCUIT_CLOS = "CIRCUIT_CLOS";
    public static final String DECISION_INCOHERENTE = "DECISION_INCOHERENTE";
    public static final String CIRCUIT_DEJA_OUVERT = "CIRCUIT_DEJA_OUVERT";
    public static final String AUCUNE_REGLE = "AUCUNE_REGLE";
    public static final String VALIDATEUR_DEJA_DECIDE = "VALIDATEUR_DEJA_DECIDE";
    public static final String DOCUMENT_NON_VALIDE = "DOCUMENT_NON_VALIDE";
    public static final String DOCUMENT_EN_CORBEILLE = "DOCUMENT_EN_CORBEILLE";
    public static final String NON_AUTORISE = "NON_AUTORISE";

    public ErreurWorkflowException(HttpStatus statut, String code, String message) {
        super(statut, code, message);
    }

    public static ErreurWorkflowException conflit(String code, String message) {
        return new ErreurWorkflowException(HttpStatus.CONFLICT, code, message);
    }

    public static ErreurWorkflowException invalide(String code, String message) {
        return new ErreurWorkflowException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ErreurWorkflowException interdit(String code, String message) {
        return new ErreurWorkflowException(HttpStatus.FORBIDDEN, code, message);
    }
}
