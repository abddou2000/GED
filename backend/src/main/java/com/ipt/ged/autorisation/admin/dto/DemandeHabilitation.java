package com.ipt.ged.autorisation.admin.dto;

import com.ipt.ged.autorisation.TypeSujet;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Nouvelle attribution (§12.2.1).
 *
 * @param roleId          rôle attribué ; facultatif pour une rupture d'héritage seule
 * @param noeudId         nœud cible ; avec {@code documentId} absent = portée globale
 * @param documentId      document cible (document isolé)
 * @param ruptureHeritage rupture sur le nœud : sans rôle, le sujet n'y a plus aucun droit hérité
 */
public record DemandeHabilitation(
        @NotNull(message = "La nature du sujet est obligatoire") TypeSujet sujetType,
        @NotNull(message = "Le sujet est obligatoire") UUID sujetId,
        UUID roleId,
        UUID noeudId,
        UUID documentId,
        boolean ruptureHeritage) {
}
