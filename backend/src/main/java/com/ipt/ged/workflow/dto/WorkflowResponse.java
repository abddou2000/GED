package com.ipt.ged.workflow.dto;

import com.ipt.ged.workflow.WorkflowGed;

import java.util.List;
import java.util.UUID;

/**
 * Données renvoyées au frontend pour une règle de workflow.
 */
public record WorkflowResponse(
        UUID id,
        String name,
        List<StepResponse> steps,
        List<String> workspaces
) {
    /** Validateur : nommé ({@code employeId}) ou par rôle ({@code roleId}, périmètre). */
    public record StepResponse(
            UUID id,
            UUID employeId,
            String employeFullName,
            String label,
            int stepOrder,
            UUID roleId,
            String roleCode,
            UUID perimetreNoeudId
    ) {}

    public static WorkflowResponse from(WorkflowGed w) {
        List<StepResponse> steps = w.getSteps().stream()
                .map(s -> new StepResponse(
                        s.getId(),
                        s.getEmploye() != null ? s.getEmploye().getId() : null,
                        s.getEmploye() != null ? s.getEmploye().getFullName() : null,
                        s.getLabel(),
                        s.getStepOrder(),
                        s.getRole() != null ? s.getRole().getId() : null,
                        s.getRole() != null ? s.getRole().getCode() : null,
                        s.getPerimetreNoeudId()))
                .toList();
        return new WorkflowResponse(w.getId(), w.getName(), steps, List.of());
    }
}
