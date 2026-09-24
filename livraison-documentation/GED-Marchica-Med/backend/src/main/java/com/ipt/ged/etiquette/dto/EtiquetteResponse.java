package com.ipt.ged.etiquette.dto;

import com.ipt.ged.etiquette.Etiquette;

/**
 * Données renvoyées au frontend pour une étiquette.
 */
public record EtiquetteResponse(
        Long id,
        String code,
        String tag,
        String couleur
) {
    public static EtiquetteResponse from(Etiquette e) {
        return new EtiquetteResponse(e.getId(), e.getCode(), e.getTag(), e.getCouleur());
    }
}
