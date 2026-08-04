package com.ipt.ged.planindexation.dto;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.planindexation.PlanIndexation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Données renvoyées au frontend pour un plan d'indexation, avec un aperçu
 * du nommage composé à partir des index (dans l'ordre).
 */
public record PlanIndexationResponse(
        Long id,
        String code,
        String nomDuPlan,
        boolean modeIndexation,
        boolean manuel,
        boolean majuscule,
        String separateur,
        List<Ref> indices,
        int indexCount,
        String preview
) {
    public record Ref(Long id, String label) {}

    public static PlanIndexationResponse from(PlanIndexation p) {
        List<Ref> refs = p.getIndices().stream()
                .map(x -> new Ref(x.getId(), x.getNomIndex())).toList();
        String sep = p.getSeparateur() != null ? p.getSeparateur() : "_";
        String joined = p.getIndices().stream()
                .map(IndexField::getNomIndex).collect(Collectors.joining(sep));
        String preview = p.isMajuscule() ? joined.toUpperCase() : joined.toLowerCase();
        return new PlanIndexationResponse(
                p.getId(), p.getCode(), p.getNomDuPlan(),
                p.isModeIndexation(), p.isManuel(), p.isMajuscule(), sep,
                refs, refs.size(), preview);
    }
}
