package com.ipt.ged.indexation.dto;

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
    Long workspaceId,
    Long typeDocumentId,
    List<FiltreIndex> criteres,
    Long grouperPar
) {
    /**
     * Un filtre sur un index.
     * <p>TEXTE : {@code valeur} (contient) — LISTE : {@code valeur} (égal)
     * <br>DATE / NOMBRE : {@code de} et/ou {@code a} (bornes incluses)
     */
    public record FiltreIndex(Long indexFieldId, String valeur, String de, String a) {}
}
