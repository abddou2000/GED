package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/** Version prévisualisée dans la visionneuse (§6.1.6 : événement d'audit distinct du téléchargement). */
public record ApercuConsulte(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                             UUID cleFichierId) implements EvenementDocument {
    @Override
    public String type() {
        return "APERCU_CONSULTE";
    }
}
