package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/** Document mis en corbeille (suppression douce, §12.5 ; aucun fichier effacé). */
public record DocumentSupprime(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                               String nom) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_SUPPRIME";
    }
}
