package com.ipt.ged.typedocument.dto;

import com.ipt.ged.typedocument.TypeDocument;

import java.util.Arrays;
import java.util.List;

/**
 * Données renvoyées au frontend pour un type de document.
 */
public record TypeDocumentResponse(
        Long id,
        String code,
        String typeDeDocument,
        String description,
        Ref workspace,
        Ref planIndexation,
        List<String> typeAutorise,
        int tailleMaxMo
) {
    public record Ref(Long id, String label) {}

    public static TypeDocumentResponse from(TypeDocument t) {
        List<String> types = (t.getTypeAutorise() == null || t.getTypeAutorise().isBlank())
                ? List.of()
                : Arrays.stream(t.getTypeAutorise().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new TypeDocumentResponse(
                t.getId(), t.getCode(), t.getTypeDeDocument(), t.getDescription(),
                t.getWorkspace() != null ? new Ref(t.getWorkspace().getId(), t.getWorkspace().getName()) : null,
                t.getPlanIndexation() != null
                        ? new Ref(t.getPlanIndexation().getId(), t.getPlanIndexation().getNomDuPlan()) : null,
                types, t.getTailleMaxMo());
    }
}
