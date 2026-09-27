package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.document.DocumentVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Résout une version de document en fichier chiffré, à partir de
 * {@code version_document} ({@code cle_fichier_id}, {@code type_mime},
 * nom d'origine). Une version pas encore reprise dans le stockage chiffré
 * n'a pas d'aperçu (404).
 */
@Component
public class ResolveurFichierVersionJpa implements ResolveurFichierVersion {

    private final DocumentVersionRepository versions;

    public ResolveurFichierVersionJpa(DocumentVersionRepository versions) {
        this.versions = versions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FichierVersion> resoudre(UUID versionId) {
        return versions.findById(versionId)
                .filter(v -> v.getCleFichierId() != null)
                .map(v -> new FichierVersion(v.getId(), v.getDocument().getId(), v.getCleFichierId(),
                        v.getTypeMime(), v.getFileName()));
    }
}
