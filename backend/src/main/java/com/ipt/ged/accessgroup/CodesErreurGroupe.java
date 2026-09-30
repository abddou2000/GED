package com.ipt.ged.accessgroup;

/** Codes d'erreur stables de l'administration des groupes GED (DAT §5.3.2). */
public final class CodesErreurGroupe {

    private CodesErreurGroupe() {}

    /**
     * 422 — un membre demandé n'est pas une fiche employé connue ; la réponse
     * porte {@code identifiantsInconnus}. Rien n'est écrit (ANO-F-009).
     */
    public static final String MEMBRES_INCONNUS = "MEMBRES_INCONNUS";

    /** 422 — un espace demandé n'existe pas ; la réponse porte {@code identifiantsInconnus}. */
    public static final String ESPACES_INCONNUS = "ESPACES_INCONNUS";
}
