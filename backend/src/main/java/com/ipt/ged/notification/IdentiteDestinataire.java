package com.ipt.ged.notification;

import java.util.UUID;

/**
 * <b>Point d'extension</b> : identité GED de l'utilisateur de la requête, celle
 * sous laquelle ses notifications sont rangées. Par défaut l'identifiant de
 * l'employé du jeton ({@code ActeurCourant.employeId()}) ; à la fusion avec le
 * lot identité (E2), l'identifiant d'utilisateur GED
 * ({@code ActeurCourant.utilisateurId()}), qui est aussi celui des sujets
 * d'habilitation.
 */
@FunctionalInterface
public interface IdentiteDestinataire {

    /** {@code null} hors requête d'un utilisateur (application, tâche technique). */
    UUID courante();
}
