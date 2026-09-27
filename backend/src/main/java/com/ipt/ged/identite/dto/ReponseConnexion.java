package com.ipt.ged.identite.dto;

/**
 * Réponse d'une connexion ou d'un renouvellement : le jeton d'accès (à garder en
 * mémoire côté navigateur) et l'identité. Le jeton de renouvellement, lui, ne
 * figure jamais dans le corps : il voyage dans un cookie {@code HttpOnly}.
 */
public record ReponseConnexion(String token, String tokenType, long expiresIn, IdentiteResponse utilisateur) {
}
