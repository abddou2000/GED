package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Document désarchivé (§12.6, réservé à l'Agent d'archive et à l'Administrateur), empreinte revérifiée. */
public record DocumentDesarchive(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                                 String empreinte) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_DESARCHIVE";
    }

    @Override
    public Map<String, Object> avant() {
        return Map.of("statutConservation", "ARCHIVE");
    }

    @Override
    public Map<String, Object> apres() {
        return Map.of("statutConservation", "ACTIF");
    }
}
