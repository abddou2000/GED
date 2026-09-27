package com.ipt.ged.depot;

/**
 * Issue de l'indexation d'un document déposé (§12.11, temps 2), portée par
 * {@code document.statut_indexation}.
 */
public enum IssueIndexation {
    /** Métadonnées du plan enregistrées. */
    INDEXE,
    /** Le type ne porte pas de plan d'indexation : cas valide, rien à saisir. */
    SANS_PLAN,
    /**
     * Métadonnées absentes ou dont l'écriture a échoué : le document est reçu,
     * son fichier conservé, l'indexation reste à faire (reprenable).
     */
    A_INDEXER
}
