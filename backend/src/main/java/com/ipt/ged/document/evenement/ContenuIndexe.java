package com.ipt.ged.document.evenement;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Texte d'une version extrait et indexé par le worker OCR : le document est
 * interrogeable (acteur {@link Acteur#SYSTEME}).
 *
 * @param delaiDisponibilite délai depuis le dépôt (métrique {@code ged.ocr.delai.disponibilite}).
 */
public record ContenuIndexe(UUID documentId, UUID versionId, Acteur acteur, Instant survenuLe,
                            int nbPages, String provenance, Duration delaiDisponibilite) implements EvenementDocument {
    @Override
    public String type() {
        return "CONTENU_INDEXE";
    }
}
