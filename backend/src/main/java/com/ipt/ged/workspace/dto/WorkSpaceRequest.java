package com.ipt.ged.workspace.dto;

import com.ipt.ged.workspace.WorkspaceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Données reçues pour créer / modifier un espace de travail.
 */
public record WorkSpaceRequest(
        @NotBlank(message = "Le nom est obligatoire")
        String name,

        @NotBlank(message = "Le code est obligatoire")
        String code,

        String description,

        WorkspaceStatus status,

        @NotNull(message = "Le propriétaire est obligatoire")
        UUID employeId,

        UUID parentId,

        /**
         * Règle de workflow du nœud (§12.8) ; facultative : un dossier sans
         * règle suit celle de ses ancêtres.
         */
        UUID workflowId,

        /**
         * Usage d'un ESPACE (R-03, D12) : METIER (défaut) ou ECHANGE. Ignoré pour
         * un dossier, qui a toujours l'usage de son espace.
         */
        com.ipt.ged.workspace.UsageEspace usageEspace
) {}
