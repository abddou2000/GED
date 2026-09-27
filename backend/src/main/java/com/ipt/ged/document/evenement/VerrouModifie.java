package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/** Document verrouillé ou libéré. */
public record VerrouModifie(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                            boolean avant, boolean apres) implements EvenementDocument {
    @Override
    public String type() {
        return apres ? "DOCUMENT_VERROUILLE" : "DOCUMENT_DEVERROUILLE";
    }
}
