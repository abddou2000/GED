package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/**
 * Fiche d'un document consultée (catalogue {@code DOCUMENT_CONSULTE}, dossier
 * fonctionnel §4.9.4, ANO-E4-001) : tracée après le contrôle d'accès, jamais
 * pour un refus (celui-ci a sa propre trace).
 */
public record DocumentConsulte(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe)
        implements EvenementDocument {

    @Override
    public String type() {
        return "DOCUMENT_CONSULTE";
    }
}
