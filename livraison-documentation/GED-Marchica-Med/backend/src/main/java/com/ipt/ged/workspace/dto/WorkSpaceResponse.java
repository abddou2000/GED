package com.ipt.ged.workspace.dto;

import com.ipt.ged.workspace.WorkSpace;

import java.util.List;

/**
 * Données renvoyées au frontend pour un espace de travail (colonnes de la liste).
 */
public record WorkSpaceResponse(
        Long id,
        String name,
        String code,
        String description,
        String status,
        /**
         * Espace en corbeille. {@code status} ne dit que ACTIF / ARCHIVE : la
         * fiche d'un espace supprimé annonçait donc « Statut : Actif », alors
         * que plus aucune écriture n'y est acceptée. Les deux informations sont
         * distinctes et doivent l'être toutes les deux.
         */
        boolean deleted,
        Ref owner,
        Ref parent,
        Ref workflow,
        long childrenCount,
        List<Ref> accessGroups
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(Long id, String label) {}

    /**
     * Les groupes sont chargés paresseusement : hors transaction la collection
     * n'est pas initialisée, et lever l'exception ici priverait la liste entière
     * d'une réponse pour une information secondaire.
     */
    private static List<Ref> groupes(WorkSpace w) {
        try {
            return w.getAccessGroups().stream()
                    .map(g -> new Ref(g.getId(), g.getName()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static WorkSpaceResponse from(WorkSpace w, long childrenCount) {
        return new WorkSpaceResponse(
                w.getId(),
                w.getName(),
                w.getCode(),
                w.getDescription(),
                w.getStatus().name(),
                w.isDeleted(),
                w.getOwner() != null ? new Ref(w.getOwner().getId(), w.getOwner().getFullName()) : null,
                w.getParent() != null ? new Ref(w.getParent().getId(), w.getParent().getName()) : null,
                w.getWorkflow() != null ? new Ref(w.getWorkflow().getId(), w.getWorkflow().getName()) : null,
                childrenCount,
                groupes(w)
        );
    }
}
