package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Document inclus dans un export de dossier (§12.10) : un événement par document exporté. */
public record DocumentExporte(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                              UUID exportId, String empreinte) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_EXPORTE";
    }

    @Override
    public Map<String, Object> apres() {
        return exportId != null ? Map.of("export", exportId, "empreinte", String.valueOf(empreinte))
                : Map.of("empreinte", String.valueOf(empreinte));
    }
}
