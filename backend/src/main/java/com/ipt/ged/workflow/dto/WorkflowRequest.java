package com.ipt.ged.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Données reçues pour créer / modifier un circuit de workflow.
 * Reproduit les règles de l'écran « Règles de Workflow » :
 * nom obligatoire + au moins une étape, chaque étape ayant un approbateur et un libellé.
 */
public record WorkflowRequest(
        @NotBlank(message = "Le nom du Workflow est obligatoire")
        String name,

        @NotEmpty(message = "Au moins une étape est requise")
        @Valid
        List<StepRequest> steps
) {
    /**
     * Un validateur : {@code employeId} (nommé) OU {@code roleId} (par rôle,
     * sur {@code perimetreNoeudId} ou, à défaut, l'emplacement du document).
     * {@code stepOrder} n'est qu'un ordre d'affichage.
     */
    public record StepRequest(
            UUID employeId,

            UUID roleId,

            UUID perimetreNoeudId,

            @NotBlank(message = "Le libellé de l'étape est obligatoire")
            @Size(max = 255)
            String label,

            int stepOrder
    ) {}
}
