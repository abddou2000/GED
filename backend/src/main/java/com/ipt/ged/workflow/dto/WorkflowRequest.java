package com.ipt.ged.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

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
    public record StepRequest(
            @NotNull(message = "L'approbateur est obligatoire")
            Long employeId,

            @NotBlank(message = "Le libellé de l'étape est obligatoire")
            @Size(max = 255)
            String label,

            int stepOrder
    ) {}
}
