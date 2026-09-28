package com.ipt.ged.workflow.api;

/**
 * Opérations de workflow soumises à la portée d'une clé d'API (D8) ; elles
 * correspondent à {@code OperationApi.WORKFLOW_PILOTAGE} et
 * {@code OperationApi.WORKFLOW_DECISION} du lot clés d'API (dev2).
 */
public enum OperationWorkflow {
    /** Règles, validateurs, ouverture, annulation, réaffectation, diffusion. */
    PILOTAGE,
    /** Décision rendue pour le compte d'un validateur. */
    DECISION
}
