package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/** Document sorti de la corbeille. */
public record DocumentRestaure(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                               String nom) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_RESTAURE";
    }
}
