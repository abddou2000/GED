package com.ipt.ged.indexation.dto;

import com.ipt.ged.common.erreur.ChampsInconnusSignales;

import java.util.List;
import java.util.UUID;

/**
 * Corps d'une recherche multi-critères ({@code POST /indexation/recherche}).
 * Un champ inconnu est ignoré et signalé (en-tête {@code GED-Champs-Ignores}, P-08).
 *
 * @param workspaceId    restreindre à un espace (facultatif)
 * @param typeDocumentId restreindre à un type de document (facultatif)
 * @param criteres       filtres portant sur les index
 * @param grouperPar     identifiant de l'index de groupage retenu (facultatif)
 * @param page           rang de page, à partir de 0
 * @param taille         documents par page : 50 par défaut, plafonnée à 200 (DAT §5.3.2)
 */
@ChampsInconnusSignales
public record RechercheRequest(
    UUID workspaceId,
    UUID typeDocumentId,
    List<FiltreIndex> criteres,
    UUID grouperPar,
    /** Documents archivés : INCLURE (défaut), EXCLURE ou SEULEMENT (§12.6). */
    String archives,
    /** Canal du dépôt (T-040) : INTERFACE, API, BUREAU_ORDRE, REPRISE ; tous si absent. */
    String canal,
    Integer page,
    Integer taille
) {
    /** Sans pagination explicite : première page, taille par défaut. */
    public RechercheRequest(UUID workspaceId, UUID typeDocumentId, List<FiltreIndex> criteres, UUID grouperPar,
                            String archives, String canal) {
        this(workspaceId, typeDocumentId, criteres, grouperPar, archives, canal, null, null);
    }

    /**
     * Un filtre sur un index.
     * <p>TEXTE : {@code valeur} (contient) — LISTE : {@code valeur} (égal)
     * <br>DATE / NOMBRE : {@code de} et/ou {@code a} (bornes incluses)
     */
    @ChampsInconnusSignales
    public record FiltreIndex(UUID indexFieldId, String valeur, String de, String a) {}
}
