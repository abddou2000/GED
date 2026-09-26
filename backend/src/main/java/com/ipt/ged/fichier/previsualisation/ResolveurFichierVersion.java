package com.ipt.ged.fichier.previsualisation;

import java.util.Optional;
import java.util.UUID;

/**
 * Traduit l'identifiant public d'une version de document en fichier stocké.
 *
 * <p>Point de branchement sur le modèle de données : à implémenter sur
 * {@code version_document} ({@code id}, {@code fichier_id}, {@code type_mime},
 * nom d'origine) quand le modèle UUID sera intégré. Tant qu'aucune
 * implémentation n'existe, le contrôleur de prévisualisation reste désactivé.
 */
public interface ResolveurFichierVersion {

    Optional<FichierVersion> resoudre(String versionId);

    /**
     * @param documentId identifiant du document, pour le contrôle des droits.
     */
    record FichierVersion(String versionId, String documentId, UUID fichierId, String typeMime, String nomOrigine) {
    }
}
