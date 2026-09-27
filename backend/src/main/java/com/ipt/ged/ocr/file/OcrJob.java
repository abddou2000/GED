package com.ipt.ged.ocr.file;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne de {@code ocr_job}.
 *
 * @param deposeLe    instant du dépôt de la version : origine du délai
 *                    « dépôt → disponibilité en recherche » ({@code ocr_delai_disponibilite}) ;
 * @param tentatives  nombre d'exécutions déjà commencées (réservations).
 */
public record OcrJob(UUID id, UUID documentId, UUID versionId, UUID fichierId, String typeMime, String langue,
                     StatutOcr statut, int tentatives, Instant prochaineTentativeLe, String verrouillePar,
                     Instant verrouilleJusquA, String motifEchec, Integer nbPages, Instant deposeLe,
                     Instant creeLe, Instant termineLe) {
}
