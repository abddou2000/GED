package com.ipt.ged.identite.evenement;

import com.ipt.ged.identite.session.MotifRevocation;

import java.time.Instant;
import java.util.UUID;

/**
 * Sessions d'un utilisateur révoquées : déconnexion, révocation par
 * l'Administrateur ou réutilisation d'un jeton consommé (vol présumé).
 *
 * @param parUtilisateurId auteur de la révocation ; {@code null} pour une révocation automatique
 * @param nombre           lignes de session révoquées
 */
public record SessionsRevoquees(UUID utilisateurId, UUID parUtilisateurId, MotifRevocation motif,
                                int nombre, Instant instant) {
}
