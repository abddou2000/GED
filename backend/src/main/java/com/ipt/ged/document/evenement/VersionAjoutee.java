package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Nouvelle version déposée ; elle devient la version courante.
 *
 * @param versionPrecedenteId version courante avant l'opération.
 */
public record VersionAjoutee(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                             UUID versionPrecedenteId, String nomFichier, String observation,
                             UUID cleFichierId, String empreinte, String typeMime, long tailleOctets,
                             String statutOcr) implements EvenementDocument {
    @Override
    public String type() {
        return "VERSION_AJOUTEE";
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
        m.put("fichier", nomFichier);
        m.put("empreinte", empreinte);
        m.put("typeMime", typeMime);
        m.put("tailleOctets", tailleOctets);
        return m;
    }

    @Override
    public String motif() {
        return observation;
    }
}
