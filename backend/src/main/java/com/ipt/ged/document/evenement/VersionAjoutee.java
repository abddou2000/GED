package com.ipt.ged.document.evenement;

import java.time.Instant;
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
}
