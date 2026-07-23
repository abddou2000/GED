package com.ipt.ged.workspace.dto;

import com.ipt.ged.workspace.WorkspaceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        Long employeId,

        Long parentId,

        @NotNull(message = "La règle de workflow est obligatoire")
        Long workflowId
) {}
