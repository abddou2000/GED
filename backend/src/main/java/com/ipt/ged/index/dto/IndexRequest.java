package com.ipt.ged.index.dto;

import com.ipt.ged.index.IndexFieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Données reçues pour créer / modifier un index.
 */
public record IndexRequest(
        @NotBlank(message = "Le code est obligatoire")
        String code,

        @NotBlank(message = "Le nom de l'index est obligatoire")
        String nomIndex,

        @NotNull(message = "Le type de champ est obligatoire")
        IndexFieldType fieldType,

        String valeurs,

        String valeurParDefaut,

        boolean obligatoire,

        boolean indexePourRecherche,

        boolean indexDeGroupage
) {}
