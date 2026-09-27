package com.ipt.ged.identite.dto;

import java.util.List;
import java.util.UUID;

/**
 * Identité de l'appelant, telle que l'interface l'affiche.
 *
 * <p>Les rôles servent uniquement au confort d'affichage (menus, page d'accueil
 * vide d'un compte sans rôle) : le serveur les revérifie à chaque requête.
 *
 * @param id          identifiant technique GED
 * @param identifiant {@code sAMAccountName}
 * @param employeId   personne métier (dépôts, dossiers, circuits)
 * @param email       courriel lu dans l'annuaire (cache), pour information
 * @param roles       rôles détenus, toute portée confondue
 * @param permissions permissions exercées quelque part (lot E3) : l'interface
 *                    masque les menus et actions correspondants — confort
 *                    seulement, le serveur décide à chaque requête
 */
public record IdentiteResponse(UUID id, String identifiant, UUID employeId, String fullName, String email,
                               String direction, List<String> roles, List<String> permissions) {
}
