package com.ipt.ged.index.dto;

import com.ipt.ged.index.IndexField;

import java.util.UUID;

/**
 * Données renvoyées au frontend pour un index.
 */
public record IndexResponse(
        UUID id,
        String code,
        String nomIndex,
        String fieldType,
        String valeurs,
        String valeurParDefaut,
        boolean obligatoire,
        boolean indexePourRecherche,
        boolean indexDeGroupage
) {
    public static IndexResponse from(IndexField x) {
        return new IndexResponse(
                x.getId(), x.getCode(), x.getNomIndex(), x.getFieldType().name(),
                x.getValeurs(), x.getValeurParDefaut(),
                x.isObligatoire(), x.isIndexePourRecherche(), x.isIndexDeGroupage());
    }
}
