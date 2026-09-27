package com.ipt.ged.cleapi;

/**
 * Opérations qu'une portée de clé peut autoriser (DAT §5.4 : « création de
 * dossier, dépôt, consultation, recherche, versement, rattachement,
 * consultation des droits »).
 *
 * <p>Les opérations de circuit de validation sont prévues dès maintenant
 * (revue technique D8 : le workflow doit être pilotable par API, lot E8-API) :
 * la portée d'une clé les désignera sans changement de schéma, la colonne
 * {@code operations} portant des codes.
 */
public enum OperationApi {
    CREATION_DOSSIER,
    DEPOT,
    CONSULTATION,
    RECHERCHE,
    VERSEMENT,
    RATTACHEMENT,
    CONSULTATION_DROITS,

    // --- D8, lot E8-API (vague 5) ---
    /** Désigner les validateurs, ouvrir et annuler un circuit. */
    WORKFLOW_PILOTAGE,
    /** Rendre une décision de validation (pour le compte d'un validateur délégué). */
    WORKFLOW_DECISION
}
