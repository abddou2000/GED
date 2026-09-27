package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Document verrouillé ou libéré. */
public record VerrouModifie(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                            boolean verrouilleAvant, boolean verrouilleApres, String motifVerrou)
        implements EvenementDocument {

    /** Sans motif (compatibilité : le motif du verrou est facultatif, lot E7 §12.8). */
    public VerrouModifie(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                         boolean verrouilleAvant, boolean verrouilleApres) {
        this(documentId, versionId, acteur, survenuLe, verrouilleAvant, verrouilleApres, null);
    }

    /** Motif saisi par l'Administrateur à la pose ou à la levée du verrou. */
    @Override
    public String motif() {
        return motifVerrou;
    }
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
