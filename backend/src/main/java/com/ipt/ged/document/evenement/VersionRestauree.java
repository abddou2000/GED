package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Une version antérieure redevient la version courante (avant : {@code versionPrecedenteId}). */
public record VersionRestauree(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                               UUID versionPrecedenteId, String statutOcr) implements EvenementDocument {
    @Override
    public String type() {
        return "VERSION_RESTAUREE";
    }

    @Override
    public Map<String, Object> avant() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionCourante", versionPrecedenteId);
        return m;
    }

    @Override
    public Map<String, Object> apres() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionCourante", versionId);
        return m;
    }
}
