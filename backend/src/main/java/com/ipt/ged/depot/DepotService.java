package com.ipt.ged.depot;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.DocumentService;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.indexation.dto.ValeurRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dépôt et indexation en deux temps (§12.11), et dépôt avec métadonnées en une
 * seule opération (§5.3).
 *
 * <ul>
 *   <li><b>Temps 1</b> (transaction courte, {@link DocumentService#upload}) :
 *       contrôles — fiche, plan d'indexation (les métadonnées reçues sont
 *       validées <b>avant</b> toute écriture), taille, type réel, antivirus —
 *       puis fichier chiffré, document, version courante, clé et job OCR. Une
 *       fois validé, le document est <b>reçu</b> : son fichier est conservé quoi
 *       qu'il advienne ensuite.</li>
 *   <li><b>Temps 2</b> (transaction séparée, {@link IndexationAuDepot}) :
 *       métadonnées du plan. Issue {@code INDEXE}, {@code SANS_PLAN} (type sans
 *       plan) ou {@code A_INDEXER} (rien reçu, ou écriture en échec :
 *       l'indexation se reprend par l'écran d'indexation).</li>
 * </ul>
 * Le tout-ou-rien vaut à l'intérieur de chaque temps ; un incident du temps 2
 * n'annule jamais le temps 1.
 */
@Service
public class DepotService {

    private static final Logger log = LoggerFactory.getLogger(DepotService.class);

    private final DocumentService documents;
    private final MetadonneesDepot metadonnees;
    private final IndexationAuDepot indexation;

    public DepotService(DocumentService documents, MetadonneesDepot metadonnees, IndexationAuDepot indexation) {
        this.documents = documents;
        this.metadonnees = metadonnees;
        this.indexation = indexation;
    }

    /** Document reçu et issue de son indexation ; {@code ocrEnAttente} décide du 202. */
    public record ResultatDepot(DocumentResponse document, boolean ocrEnAttente) {
    }

    public ResultatDepot deposer(MultipartFile fichier, String nom, UUID typeDocumentId, String dateExpiration,
                                 UUID deposantId, List<UUID> etiquetteIds, Confidentialite confidentialite,
                                 String metadonneesJson) {
        return deposer(fichier, nom, typeDocumentId, dateExpiration, deposantId, etiquetteIds, confidentialite,
                metadonneesJson, null, null);
    }

    /**
     * @param objet        objet du document (socle commun, lot E7)
     * @param dateDocument date du document ; date de dépôt si absente
     */
    public ResultatDepot deposer(MultipartFile fichier, String nom, UUID typeDocumentId, String dateExpiration,
                                 UUID deposantId, List<UUID> etiquetteIds, Confidentialite confidentialite,
                                 String metadonneesJson, String objet, String dateDocument) {
        return deposer(fichier, nom, typeDocumentId, dateExpiration, deposantId, etiquetteIds, confidentialite,
                metadonneesJson, objet, dateDocument, null);
    }

    /**
     * @param emplacementId dossier où ranger le document dans un espace d'échange (D12) ;
     *                      absent = dossier du type
     */
    public ResultatDepot deposer(MultipartFile fichier, String nom, UUID typeDocumentId, String dateExpiration,
                                 UUID deposantId, List<UUID> etiquetteIds, Confidentialite confidentialite,
                                 String metadonneesJson, String objet, String dateDocument, UUID emplacementId) {
        // Appelé dans une transaction englobante (tests transactionnels), les
        // deux temps la rejoignent : un temps 2 dans une transaction nouvelle
        // attendrait le document non validé du temps 1. En service (contrôleur),
        // il n'y en a jamais : chaque temps a sa transaction.
        boolean englobante = TransactionSynchronizationManager.isActualTransactionActive();
        Optional<ValeurRequest> valeurs = metadonnees.lire(metadonneesJson, typeDocumentId);

        // Les métadonnées sont validées une seule fois, ici (MetadonneesDepot),
        // et écrites au temps 2 : le temps 1 ne les reçoit pas.
        DocumentResponse recu = documents.upload(fichier, nom, typeDocumentId, dateExpiration, deposantId, etiquetteIds,
                confidentialite, null, objet, dateDocument, emplacementId);
        boolean ocrEnAttente = "EN_ATTENTE_OCR".equals(recu.statutOcr());

        if (IssueIndexation.SANS_PLAN.name().equals(recu.statutIndexation()) || valeurs.isEmpty()) {
            return new ResultatDepot(recu, ocrEnAttente);
        }
        try {
            if (englobante) indexation.indexerDansLaTransaction(recu.id(), valeurs.get());
            else indexation.indexer(recu.id(), valeurs.get());
        } catch (RuntimeException e) {
            log.warn("Dépôt {} reçu, indexation à reprendre (A_INDEXER) : {}", recu.id(), e.getMessage());
            return new ResultatDepot(recu.avecIndexation(IssueIndexation.A_INDEXER.name(),
                    "Métadonnées non enregistrées : " + e.getMessage()), ocrEnAttente);
        }
        // Relu après le temps 2 : l'indexation a pu composer le nom (charte).
        return new ResultatDepot(documents.relire(recu.id()), ocrEnAttente);
    }
}
