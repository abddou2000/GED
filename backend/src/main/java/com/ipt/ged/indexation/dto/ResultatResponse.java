package com.ipt.ged.indexation.dto;

import java.util.UUID;
import java.util.List;

/** Un document trouvé, accompagné des valeurs d'index qui le décrivent. */
public record ResultatResponse(
    UUID id,
    String name,
    String extension,
    String sizeLabel,
    String workspace,
    String typeDocument,
    String expirationDate,
    /** Référence composée depuis le plan ; null tant que l'indexation n'a pas été confirmée. */
    String reference,
    List<ValeurResponse> valeurs
) {
    /** Une valeur d'index affichée sur le résultat. */
    public record ValeurResponse(UUID indexFieldId, String code, String libelle, String valeur) {}
}
