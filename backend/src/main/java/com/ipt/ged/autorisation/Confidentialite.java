package com.ipt.ged.autorisation;

/**
 * Niveau de confidentialité d'un document (dossier technique §12.3).
 *
 * <p>L'accès effectif est une INTERSECTION : droit résolu sur l'un des
 * emplacements du document <b>et</b> prédicat du niveau.
 * <ul>
 *   <li>{@link #PUBLIC} : aucune restriction supplémentaire ;</li>
 *   <li>{@link #PRIVE} : le sujet est le déposant, ou porte {@code VOIR_PRIVE} ;</li>
 *   <li>{@link #CONFIDENTIEL} : le sujet est désigné, ou porte {@code VOIR_CONFIDENTIEL}.</li>
 * </ul>
 */
public enum Confidentialite {
    PUBLIC,
    PRIVE,
    CONFIDENTIEL
}
