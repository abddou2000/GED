package com.ipt.ged.planindexation.metamodele;

import com.ipt.ged.planindexation.PlanIndexation;

import java.util.List;
import java.util.Optional;

/** Définition figée d'un plan : ses index, dans l'ordre du plan. */
public record DefinitionPlan(List<ChampPlan> champs) {

    public static final DefinitionPlan VIDE = new DefinitionPlan(List.of());

    public DefinitionPlan {
        champs = champs == null ? List.of() : List.copyOf(champs);
    }

    /** Définition courante d'un plan : ses index hors corbeille. */
    public static DefinitionPlan depuis(PlanIndexation plan) {
        if (plan == null) return VIDE;
        return new DefinitionPlan(plan.getIndices().stream().filter(i -> !i.isSupprime())
                .map(ChampPlan::depuis).toList());
    }

    /** Index désigné par son code (insensible à la casse) ou son identifiant. */
    public Optional<ChampPlan> champ(String cle) {
        if (cle == null) return Optional.empty();
        String c = cle.trim();
        return champs.stream().filter(f -> f.code().equalsIgnoreCase(c) || f.id().equalsIgnoreCase(c)).findFirst();
    }
}
