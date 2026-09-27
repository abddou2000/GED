package com.ipt.ged.cycledevie;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.document.evenement.Acteur;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Archivage d'un dossier entier demandé, ou drapeau retiré (§12.6, D10) :
 * codes {@code ESPACE_ARCHIVE} et {@code ESPACE_DESARCHIVE} du catalogue
 * d'audit (les dossiers sont des espaces jusqu'au lot E3). Chaque document
 * archivé par le job a en plus son propre événement {@code DOCUMENT_ARCHIVE}.
 */
public record EvenementDossier(String action, UUID dossierId, String nom, Acteur acteur, UUID jobId, Integer documents)
        implements EvenementAudit {

    static EvenementDossier archivage(UUID dossierId, String nom, Acteur acteur, UUID jobId, int documents) {
        return new EvenementDossier("ESPACE_ARCHIVE", dossierId, nom, acteur, jobId, documents);
    }

    static EvenementDossier desarchivage(UUID dossierId, String nom, Acteur acteur) {
        return new EvenementDossier("ESPACE_DESARCHIVE", dossierId, nom, acteur, null, null);
    }

    @Override
    public String objetType() {
        return "ESPACE";
    }

    @Override
    public UUID objetId() {
        return dossierId;
    }

    @Override
    public UUID acteurUtilisateurId() {
        return acteur != null ? acteur.employeId() : null;
    }

    @Override
    public UUID acteurApplicationId() {
        return acteur != null ? acteur.applicationId() : null;
    }

    @Override
    public Map<String, Object> apres() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dossier", nom);
        m.put("archive", "ESPACE_ARCHIVE".equals(action));
        if (jobId != null) m.put("jobArchivage", jobId);
        if (documents != null) m.put("documents", documents);
        return m;
    }
}
