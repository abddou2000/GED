package com.ipt.ged.security.dto;

/**
 * Ce que l'API renvoie après une connexion réussie.
 *
 * <p>Contient tout ce dont l'interface a besoin pour s'ouvrir sans un second
 * aller-retour : le jeton et l'identité. Aucune empreinte de mot de passe n'y
 * figure.
 *
 * <p>Les rôles et permissions ont été retirés : l'application n'a qu'un seul
 * utilisateur, il n'y a plus de bouton à afficher ou masquer selon les droits.
 */
public record ReponseConnexion(
        String token,
        String tokenType,
        long expiresIn,
        Long employeId,
        String email,
        String fullName) {
}
