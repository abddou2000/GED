package com.ipt.ged.notification;

import java.util.UUID;

/**
 * <b>Point d'extension</b> : identité GED de l'utilisateur de la requête, celle
 * sous laquelle ses notifications sont rangées ({@code ActeurCourant.utilisateurId()}
 * par défaut, identité du lot E2, la même que les sujets d'habilitation).
 */
@FunctionalInterface
public interface IdentiteDestinataire {

    /** {@code null} hors requête d'un utilisateur (application, tâche technique). */
    UUID courante();
}
