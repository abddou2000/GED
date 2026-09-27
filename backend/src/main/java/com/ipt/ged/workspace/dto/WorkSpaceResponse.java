package com.ipt.ged.workspace.dto;

import com.ipt.ged.workspace.WorkSpace;

import java.util.List;
import java.util.UUID;

/**
 * Données renvoyées au frontend pour un espace de travail (colonnes de la liste).
 */
public record WorkSpaceResponse(
        UUID id,
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
        boolean supprime,
        Ref owner,
        Ref parent,
        Ref workflow,
        long childrenCount,
        List<Ref> accessGroups
) {
    /** Référence légère (id + libellé) vers une entité liée. */
    public record Ref(UUID id, String label) {}

    /**
     * @param childrenCount sous-dossiers VISIBLES de l'appelant (P5 : les
     *                      compteurs ne portent que sur le périmètre autorisé)
     * @param groupes       groupes GED habilités sur ce nœud
     */
    public static WorkSpaceResponse from(WorkSpace w, long childrenCount, List<Ref> groupes) {
        return new WorkSpaceResponse(
                w.getId(),
                w.getName(),
                w.getCode(),
                w.getDescription(),
                w.getStatus().name(),
                w.isSupprime(),
                w.getOwner() != null ? new Ref(w.getOwner().getId(), w.getOwner().getFullName()) : null,
                w.getParent() != null ? new Ref(w.getParent().getId(), w.getParent().getName()) : null,
                w.getWorkflow() != null ? new Ref(w.getWorkflow().getId(), w.getWorkflow().getName()) : null,
                childrenCount,
                groupes
        );
    }
}
