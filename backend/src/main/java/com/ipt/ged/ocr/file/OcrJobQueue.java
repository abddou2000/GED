package com.ipt.ged.ocr.file;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * File des traitements OCR (§4.3.4). Implémentation livrée : table
 * PostgreSQL {@code ocr_job} et réservation {@code FOR UPDATE SKIP LOCKED} ;
 * l'interface permettrait une bascule vers un broker si les volumes l'exigeaient.
 */
public interface OcrJobQueue {

    /**
     * Enfile le traitement d'une version, <b>dans la transaction du dépôt</b>
     * (temps 1 du §12.11). Idempotent : une version qui a déjà un job actif
     * n'en reçoit pas un second.
     *
     * @return l'identifiant du job actif de cette version.
     */
    UUID enfiler(NouveauJob job);

    /**
     * Réserve au plus {@code nombre} jobs éligibles pour ce worker, avec un bail
     * de durée {@code bail}. Deux workers ne reçoivent jamais le même job. Un
     * job dont le bail a expiré (worker arrêté brutalement) redevient éligible.
     */
    List<OcrJob> reserver(String worker, int nombre, Duration bail);

    /** Prolonge le bail pendant un long traitement ; {@code false} si le job a été repris par un autre. */
    boolean prolonger(UUID jobId, String worker, Duration bail);

    /** Marque le job terminé (à appeler dans la transaction qui enregistre le texte). */
    boolean terminer(UUID jobId, String worker, int nbPages);

    /**
     * Enregistre un échec : nouvelle tentative programmée selon la politique de
     * reprise, ou {@link StatutOcr#OCR_ECHEC} si elle est épuisée.
     *
     * @param definitif clôture immédiate en {@code OCR_ECHEC}, sans reprise ; le
     *                  worker ne l'emploie pas (§4.3.4 : trois tentatives pour tout
     *                  échec, T-034) ;
     * @return le statut résultant.
     */
    StatutOcr echouer(UUID jobId, String worker, String motif, boolean definitif);

    /** Relance manuelle d'un job en échec (écran de supervision de l'Administrateur). */
    boolean relancer(UUID jobId);

    Optional<OcrJob> trouver(UUID jobId);

    /** Dernier job de chaque version demandée : état « interrogeable » ou non. */
    Map<UUID, StatutOcr> statutsParVersion(List<UUID> versionIds);

    /** Jobs par statut, pour l'écran de supervision. */
    List<OcrJob> lister(StatutOcr statut, int limite, int decalage);

    /** Nombre de jobs en attente ou en cours (profondeur de la file). */
    long profondeur();

    /**
     * Dépôt le plus ancien du flux courant encore en attente ou en cours ; vide
     * si aucun. La reprise en est exclue : son arriéré, attendu, masquerait le
     * retard du flux, seul soumis au délai D6.
     */
    Optional<Instant> plusAncienDepotEnAttente();

    Map<StatutOcr, Long> compterParStatut();

    /**
     * Données d'un job à créer.
     *
     * @param priorite flux courant (défaut) ou reprise : le flux est toujours servi d'abord
     */
    record NouveauJob(UUID documentId, UUID versionId, UUID fichierId, String typeMime, String langue,
                      Instant deposeLe, PrioriteOcr priorite) {

        public NouveauJob {
            priorite = priorite == null ? PrioriteOcr.FLUX_COURANT : priorite;
        }

        /** Job du flux courant. */
        public NouveauJob(UUID documentId, UUID versionId, UUID fichierId, String typeMime, String langue,
                          Instant deposeLe) {
            this(documentId, versionId, fichierId, typeMime, langue, deposeLe, PrioriteOcr.FLUX_COURANT);
        }
    }
}
