package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Document archivé (§12.6) : empreinte revérifiée, copie de conservation
 * PDF/A-2 produite (ou anomalie signalée), statut ARCHIVE.
 *
 * @param copieConservation {@code VALIDE} ou {@code ECHEC} ;
 * @param motifCopie        pourquoi la copie n'a pas pu être produite ({@code null} sinon) ;
 * @param jobId             job d'archivage de dossier, {@code null} pour un archivage unitaire.
 */
public record DocumentArchive(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                              String empreinte, String copieConservation, String motifCopie,
                              UUID jobId) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_ARCHIVE";
    }

    @Override
    public Map<String, Object> avant() {
        return Map.of("statutConservation", "ACTIF");
    }

    @Override
    public Map<String, Object> apres() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statutConservation", "ARCHIVE");
        m.put("empreinte", empreinte);
        m.put("copieConservation", copieConservation);
        if (jobId != null) m.put("jobArchivage", jobId);
        return m;
    }

    @Override
    public String motif() {
        return motifCopie;
    }
}
