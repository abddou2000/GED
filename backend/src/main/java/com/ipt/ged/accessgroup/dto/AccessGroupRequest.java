package com.ipt.ged.accessgroup.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Données reçues pour créer / modifier un groupe d'accès.
 * Les droits sont normalisés côté serveur (cohérence des dépendances).
 */
public record AccessGroupRequest(
        @NotBlank(message = "Le code est obligatoire")
        String code,

        @NotBlank(message = "Le nom est obligatoire")
        String name,

        GedRightsDto rights,

        List<Long> workspaceIds,

        List<Long> userIds
) {}
