package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Purge définitive (§12.5) : lignes métier supprimées, clés de fichier (DEK)
 * détruites puis fichiers effacés. Le journal d'audit, lui, est conservé.
 *
 * @param nbVersions nombre de versions supprimées ;
 * @param nbFichiers fichiers chiffrés détruits (versions et copies de conservation).
 */
public record DocumentPurge(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                            String nom, int nbVersions, int nbFichiers) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_PURGE";
    }

    @Override
    public Map<String, Object> avant() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nom", nom);
        m.put("versions", nbVersions);
        m.put("fichiers", nbFichiers);
        return m;
    }
}
