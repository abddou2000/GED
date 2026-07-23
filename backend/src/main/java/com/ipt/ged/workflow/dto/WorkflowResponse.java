package com.ipt.ged.workflow.dto;

import com.ipt.ged.workflow.WorkflowGed;

import java.util.List;

/**
 * Données renvoyées au frontend pour une règle de workflow.
 */
public record WorkflowResponse(
        Long id,
        String name,
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
        return new WorkflowResponse(w.getId(), w.getName(), steps, List.of());
    }
}
