package com.ipt.ged.workflow.circuit;

import org.springframework.http.HttpStatus;

/**
 * Refus métier du workflow, rendu avec un code stable (contrat d'API E8, D8) :
 * les applications de l'intranet réagissent au code, pas au libellé.
 *
 * <p>Provisoire, comme {@code ConflitAutorisationException} : à la fusion du
 * contrat d'erreurs de dev2 (problem+json), elle deviendra une sous-classe
 * d'{@code ExceptionMetier} et {@link GestionErreursWorkflow} disparaîtra.
 */
public class ErreurWorkflowException extends RuntimeException {

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

    private final HttpStatus statut;
    private final String code;

    public ErreurWorkflowException(HttpStatus statut, String code, String message) {
        super(message);
        this.statut = statut;
        this.code = code;
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

    public HttpStatus statut() {
        return statut;
    }

    public String code() {
        return code;
    }
}
