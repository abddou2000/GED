package com.ipt.ged.indexation.dto;

import java.util.List;
import java.util.UUID;

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
    UUID grouperPar,
    /** Documents archivés : INCLURE (défaut), EXCLURE ou SEULEMENT (§12.6). */
    String archives,
    /** Canal du dépôt (T-040) : INTERFACE, API, BUREAU_ORDRE, REPRISE ; tous si absent. */
    String canal
) {
    /**
     * Un filtre sur un index.
     * <p>TEXTE : {@code valeur} (contient) — LISTE : {@code valeur} (égal)
     * <br>DATE / NOMBRE : {@code de} et/ou {@code a} (bornes incluses)
     */
    public record FiltreIndex(UUID indexFieldId, String valeur, String de, String a) {}
}
