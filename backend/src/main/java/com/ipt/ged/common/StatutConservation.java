package com.ipt.ged.common;

/**
 * Statut de conservation (dossier technique §12.6, décision D10), porté par le
 * document et par le nœud. ARCHIVE : lecture seule pour tous les rôles, seul
 * le désarchivage y fait exception.
 */
public enum StatutConservation {
    ACTIF,
    ARCHIVE
}
