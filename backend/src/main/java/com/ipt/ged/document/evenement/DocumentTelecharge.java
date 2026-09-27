package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/** Fichier d'une version téléchargé (distinct de l'aperçu, §6.1.6). */
public record DocumentTelecharge(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                                 String nomFichier) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_TELECHARGE";
    }
}
