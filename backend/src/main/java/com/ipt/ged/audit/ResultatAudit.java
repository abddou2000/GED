package com.ipt.ged.audit;

/** Résultat d'une action auditée (colonne {@code resultat}, DAT §7.4.1). */
public enum ResultatAudit {
    /** L'action a eu lieu. */
    SUCCES,
    /** L'action a été refusée : droits, règle métier, contrôle de fichier. */
    REFUS,
    /** L'action a été tentée et a échoué pour une raison technique. */
    ECHEC
}
