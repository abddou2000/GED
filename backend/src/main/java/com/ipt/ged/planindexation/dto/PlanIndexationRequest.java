package com.ipt.ged.planindexation.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

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

        List<UUID> indexIds,

        /**
         * Jetons composant le nom du fichier, dans l'ordre : identifiants d'index
         * du plan (« 3 ») ou clés système (« date », « year »). Vide ou absent en
         * nommage manuel.
         */
        List<String> charteIds
) {}
