package com.ipt.ged.indexation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Corps d'enregistrement des valeurs d'index d'un document. */
public record ValeurRequest(@NotNull List<Ligne> valeurs) {
    public record Ligne(@NotNull Long indexFieldId, String valeur) {}
}
