package com.ipt.ged.typedocument.dto;

import java.util.UUID;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
        int tailleMaxMo
) {}
