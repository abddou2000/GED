package com.ipt.ged.accessgroup.dto;

import com.ipt.ged.common.Limites;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Données reçues pour créer / modifier un groupe d'accès : son identité et les
 * rattachements (espaces, membres). Un ancien client qui enverrait encore un champ
 * « rights » est ignoré sans erreur, la désérialisation étant tolérante.
 *
 * <p>Les bornes de longueur sont déclarées ici, et non laissées à la base :
 * au-delà de 255 caractères, l'insertion échouait au fond du dépôt et
 * l'utilisateur recevait un 500 nu, sans savoir quel champ était en cause.
 */
public record AccessGroupRequest(
        @NotBlank(message = "Le code est obligatoire")
        @Size(max = Limites.TEXTE, message = "Le code ne peut pas dépasser " + Limites.TEXTE + " caractères")
        String code,

        @NotBlank(message = "Le nom est obligatoire")
        @Size(max = Limites.TEXTE, message = "Le nom ne peut pas dépasser " + Limites.TEXTE + " caractères")
        String name,

        List<UUID> workspaceIds,

        List<UUID> userIds
) {}
