package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/**
 * Traitement OCR définitivement en échec : le document reste consultable mais
 * « non interrogeable » (§4.3.4). Acteur {@link Acteur#SYSTEME}.
 */
public record OcrEnEchec(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                         UUID jobId, String motif) implements EvenementDocument {
    @Override
    public String type() {
        return "OCR_ECHEC";
    }
}
