package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fiche modifiée (nom, type, date d'expiration, étiquettes, activité) ou
 * valeurs d'index enregistrées.
 *
 * @param avant valeurs des seuls champs modifiés, avant l'opération ;
 * @param apres mêmes champs, après. Valeurs simples (texte, UUID, date ISO,
 *              liste d'UUID) pour être sérialisables telles quelles.
 */
public record MetadonneesModifiees(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                                   Map<String, Object> avant, Map<String, Object> apres) implements EvenementDocument {

    public MetadonneesModifiees {
        avant = avant == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(avant));
        apres = apres == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(apres));
    }

    @Override
    public String type() {
        return "METADONNEES_MODIFIEES";
    }
}
