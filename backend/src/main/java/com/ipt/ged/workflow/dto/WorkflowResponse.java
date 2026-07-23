package com.ipt.ged.workflow.dto;

import com.ipt.ged.workflow.WorkflowGed;

import java.util.List;

/**
 * Données renvoyées au frontend pour une règle — alignées sur les colonnes du design :
 * ID, Nom de la règle, Étapes, Espace de travail, Statut, Dernière modification.
 */
public record WorkflowResponse(
        Long id,
        String name,
        String status,
        String espaceDeTravail,
        String lastModified,
        List<StepResponse> steps,
        List<String> workspaces
) {
    public record StepResponse(
            Long id,
            Long employeId,
            String employeFullName,
            String label,
            int stepOrder
    ) {}

    public static WorkflowResponse from(WorkflowGed w) {
        List<StepResponse> steps = w.getSteps().stream()
                .map(s -> new StepResponse(
                        s.getId(),
                        s.getEmploye() != null ? s.getEmploye().getId() : null,
                        s.getEmploye() != null ? s.getEmploye().getFullName() : null,
                        s.getLabel(),
                        s.getStepOrder()))
                .toList();
        return new WorkflowResponse(
                w.getId(),
                w.getName(),
                w.getStatus(),
                w.getWorkspaceName(),
                w.getLastModified(),
                steps,
                List.of());
    }
}
