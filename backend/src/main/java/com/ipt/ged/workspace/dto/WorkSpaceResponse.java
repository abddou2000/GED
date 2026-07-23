package com.ipt.ged.workspace.dto;

import com.ipt.ged.workspace.WorkSpace;

/**
 * Données renvoyées au frontend pour un espace de travail (colonnes de la liste).
 */
public record WorkSpaceResponse(
        Long id,
        String name,
        String code,
        String description,
        String status,
        Ref owner,
        Ref parent,
        Ref workflow,
        long childrenCount
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(Long id, String label) {}

    public static WorkSpaceResponse from(WorkSpace w, long childrenCount) {
        return new WorkSpaceResponse(
                w.getId(),
                w.getName(),
                w.getCode(),
                w.getDescription(),
                w.getStatus().name(),
                w.getOwner() != null ? new Ref(w.getOwner().getId(), w.getOwner().getFullName()) : null,
                w.getParent() != null ? new Ref(w.getParent().getId(), w.getParent().getName()) : null,
                w.getWorkflow() != null ? new Ref(w.getWorkflow().getId(), w.getWorkflow().getName()) : null,
                childrenCount
        );
    }
}
