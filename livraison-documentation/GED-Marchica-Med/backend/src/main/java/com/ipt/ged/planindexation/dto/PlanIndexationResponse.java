package com.ipt.ged.planindexation.dto;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.planindexation.CharteNommage;
import com.ipt.ged.planindexation.PlanIndexation;

import java.util.List;

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
        /** Jetons du nom composé, dans l'ordre (ids d'index ou clés système). */
        List<String> charteIds,
        /**
         * Charte telle qu'enregistrée. Exposée brute parce qu'un plan hérité peut
         * contenir du texte libre au lieu du JSON attendu : la liste l'affiche
         * alors tel quel plutôt que de laisser la colonne vide.
         */
        String charteNommage,
        String preview
) {
    public record Ref(Long id, String label) {}

    public static PlanIndexationResponse from(PlanIndexation p) {
        List<Ref> refs = p.getIndices().stream()
                .map(x -> new Ref(x.getId(), x.getNomIndex())).toList();
        String sep = p.getSeparateur() != null ? p.getSeparateur() : "_";
        List<String> charte = CharteNommage.jetons(p.getCharteNommage());
        // Plan enregistré avant l'introduction de la charte : on retombe sur
        // l'ordre des index du plan plutôt que d'afficher un aperçu vide.
        if (charte.isEmpty()) {
            charte = p.getIndices().stream().map(x -> String.valueOf(x.getId())).toList();
        }
        return new PlanIndexationResponse(
                p.getId(), p.getCode(), p.getNomDuPlan(),
                p.isModeIndexation(), p.isManuel(), p.isMajuscule(), sep,
                refs, refs.size(), charte, p.getCharteNommage(),
                CharteNommage.apercu(charte, p.getIndices(), sep, p.isMajuscule()));
    }
}
