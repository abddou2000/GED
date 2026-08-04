package com.ipt.ged.planindexation.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Données reçues pour créer / modifier un plan d'indexation.
 * L'ordre de {@code indexIds} détermine l'ordre de nommage.
 */
public record PlanIndexationRequest(
        @NotBlank(message = "Le code est obligatoire")
        String code,

        @NotBlank(message = "Le nom du plan est obligatoire")
        String nomDuPlan,

        boolean modeIndexation,

        boolean manuel,

        boolean majuscule,

        String separateur,

        List<Long> indexIds
) {}
