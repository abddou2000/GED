package com.ipt.ged.etiquette.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Données reçues pour créer / modifier une étiquette.
 */
public record EtiquetteRequest(
        @NotBlank(message = "Le code est obligatoire")
        String code,

        @NotBlank(message = "Le libellé est obligatoire")
        String tag,

        @NotBlank(message = "La couleur est obligatoire")
        String couleur
) {}
