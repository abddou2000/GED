package com.ipt.ged.document;

/** Statut de conservation du document (§12.6), porté par le document pour tous ses emplacements. */
public enum StatutConservation {
    ACTIF,
    /** Lecture seule totale ; seul le désarchivage y fait exception. */
    ARCHIVE
}
