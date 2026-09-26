package com.ipt.ged.indexation.dto;

import java.util.UUID;
import java.util.List;

/**
 * Corps d'une recherche multi-critères.
 *
 * @param workspaceId    restreindre à un espace (facultatif)
 * @param typeDocumentId restreindre à un type de document (facultatif)
 * @param criteres       filtres portant sur les index
 * @param grouperPar     identifiant de l'index de groupage retenu (facultatif)
 */
public record RechercheRequest(
    UUID workspaceId,
    UUID typeDocumentId,
    List<FiltreIndex> criteres,
    UUID grouperPar
) {
    /**
     * Un filtre sur un index.
     * <p>TEXTE : {@code valeur} (contient) — LISTE : {@code valeur} (égal)
     * <br>DATE / NOMBRE : {@code de} et/ou {@code a} (bornes incluses)
     */
    public record FiltreIndex(UUID indexFieldId, String valeur, String de, String a) {}
}
