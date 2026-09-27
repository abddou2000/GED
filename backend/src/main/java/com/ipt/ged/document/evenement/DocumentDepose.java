package com.ipt.ged.document.evenement;

import java.time.Instant;
import java.util.UUID;

/**
 * Document déposé (temps 1 du §12.11) : fiche, version initiale et fichier
 * chiffré créés.
 *
 * @param statutOcr {@code EN_ATTENTE_OCR} si un traitement OCR a été enfilé,
 *                  {@code null} sinon.
 */
public record DocumentDepose(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                             String nom, UUID typeDocumentId, UUID workspaceId, String nomFichier,
                             UUID cleFichierId, String empreinte, String typeMime, long tailleOctets,
                             String statutOcr) implements EvenementDocument {
    @Override
    public String type() {
        return "DOCUMENT_DEPOSE";
    }
}
