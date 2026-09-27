package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Document verrouillé ou libéré. */
public record VerrouModifie(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                            boolean verrouilleAvant, boolean verrouilleApres) implements EvenementDocument {
    @Override
    public String type() {
        return verrouilleApres ? "DOCUMENT_VERROUILLE" : "DOCUMENT_DEVERROUILLE";
    }

    @Override
    public Map<String, Object> avant() {
        return Map.of("verrouille", verrouilleAvant);
    }

    @Override
    public Map<String, Object> apres() {
        return Map.of("verrouille", verrouilleApres);
    }
}
