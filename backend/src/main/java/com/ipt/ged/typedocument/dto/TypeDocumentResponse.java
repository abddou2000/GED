package com.ipt.ged.typedocument.dto;

import com.ipt.ged.typedocument.TypeDocument;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Données renvoyées au frontend pour un type de document.
 */
public record TypeDocumentResponse(
        UUID id,
        String code,
        String typeDeDocument,
        String description,
        Ref workspace,
        Ref planIndexation,
        List<String> typeAutorise,
        int tailleMaxMo,
        Integer dureeConservationMois,
        String pointDepart,
        String pointDepartIndexCode,
        String confidentialiteDefaut,
        /** Type actif : un type désactivé n'accepte plus de dépôt. */
        boolean actif,
        /** Version en vigueur du plan d'indexation, s'il y en a un. */
        Integer versionPlan
) {
    public record Ref(UUID id, String label) {}

    public static TypeDocumentResponse from(TypeDocument t) {
        return from(t, null);
    }

    public static TypeDocumentResponse from(TypeDocument t, Integer versionPlan) {
        List<String> types = (t.getTypeAutorise() == null || t.getTypeAutorise().isBlank())
                ? List.of()
                : Arrays.stream(t.getTypeAutorise().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new TypeDocumentResponse(
                t.getId(), t.getCode(), t.getTypeDeDocument(), t.getDescription(),
                t.getWorkspace() != null ? new Ref(t.getWorkspace().getId(), t.getWorkspace().getName()) : null,
                t.getPlanIndexation() != null
                        ? new Ref(t.getPlanIndexation().getId(), t.getPlanIndexation().getNomDuPlan()) : null,
                types, t.getTailleMaxMo(), t.getDureeConservationMois(),
                t.getPointDepart() != null ? t.getPointDepart().name() : null, t.getPointDepartIndexCode(),
                t.getConfidentialiteDefaut() != null ? t.getConfidentialiteDefaut().name() : null,
                t.isActif(), versionPlan);
    }
}
