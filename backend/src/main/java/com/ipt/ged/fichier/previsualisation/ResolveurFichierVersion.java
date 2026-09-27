package com.ipt.ged.fichier.previsualisation;

import java.util.Optional;
import java.util.UUID;

/**
 * Traduit l'identifiant public d'une version de document en fichier stocké.
 *
 * <p>Implémentation : {@link ResolveurFichierVersionJpa} sur {@code version_document}.
 */
public interface ResolveurFichierVersion {

    Optional<FichierVersion> resoudre(UUID versionId);

    /**
     * @param documentId identifiant du document, pour le contrôle des droits.
     */
    record FichierVersion(UUID versionId, UUID documentId, UUID fichierId, String typeMime, String nomOrigine) {
    }
}
