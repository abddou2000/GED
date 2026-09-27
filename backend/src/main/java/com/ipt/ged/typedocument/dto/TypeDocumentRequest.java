package com.ipt.ged.typedocument.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Données reçues pour créer / modifier un type de document.
 */
public record TypeDocumentRequest(
        @NotBlank(message = "Le code est obligatoire")
        String code,

        @NotBlank(message = "Le type de document est obligatoire")
        String typeDeDocument,

        @NotBlank(message = "La description est obligatoire")
        String description,

        @NotNull(message = "L'espace de travail est obligatoire")
        UUID workspaceId,

        UUID planIndexationId,

        @NotEmpty(message = "Au moins un format de fichier est requis")
        List<String> typeAutorise,

        @Min(value = 5, message = "La taille maximale est de 5 Mo minimum")
        int tailleMaxMo,

        /** Durée de conservation en mois (§12.9) ; absente = pas d'échéance. */
        @jakarta.validation.constraints.Min(value = 1, message = "La durée de conservation est d'au moins un mois")
        @jakarta.validation.constraints.Max(value = 1200, message = "La durée de conservation est de 1200 mois au plus")
        Integer dureeConservationMois,

        /** Point de départ : DATE_DOCUMENT (défaut), DATE_DEPOT ou METADONNEE. */
        com.ipt.ged.typedocument.PointDepart pointDepart,

        /** Code de l'index date du plan, pour le point de départ METADONNEE. */
        String pointDepartIndexCode,

        /** Niveau de confidentialité par défaut (§12.3) ; absent = PUBLIC. */
        com.ipt.ged.autorisation.Confidentialite confidentialiteDefaut
) {}
