package com.ipt.ged.ocr.file;

import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.LanguesOcr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enfilage d'une version dans la file OCR, appelé par le dépôt <b>dans sa
 * transaction</b> (§12.11, temps 1) : le fichier, la fiche et le job naissent
 * ensemble ou pas du tout.
 *
 * <p>Rien n'est enfilé quand la chaîne est désactivée
 * ({@code ged.ocr.chaine.actif=false}) ou quand le format n'a pas de contenu
 * textuel exploitable : le dépôt répond alors 201 au lieu de 202.
 */
public class EnfilageOcr {

    private final OcrJobQueue file;
    private final LanguesOcr langues;
    private final boolean actif;

    public EnfilageOcr(OcrJobQueue file, LanguesOcr langues, boolean actif) {
        this.file = file;
        this.langues = langues;
        this.actif = actif;
    }

    /**
     * @param codeTypeDocument fixe la langue de reconnaissance (défaut ara+fra).
     * @return {@link StatutOcr#EN_ATTENTE_OCR} si un job a été enfilé, vide sinon.
     */
    public Optional<StatutOcr> enfiler(UUID documentId, UUID versionId, UUID cleFichierId, String typeMime,
                                       String codeTypeDocument) {
        return enfiler(documentId, versionId, cleFichierId, typeMime, codeTypeDocument, PrioriteOcr.FLUX_COURANT);
    }

    /**
     * Enfilage d'une version reprise du fonds existant : servie après le flux
     * courant (R31, D6), quel que soit l'arriéré de la reprise.
     */
    public Optional<StatutOcr> enfilerReprise(UUID documentId, UUID versionId, UUID cleFichierId, String typeMime,
                                              String codeTypeDocument) {
        return enfiler(documentId, versionId, cleFichierId, typeMime, codeTypeDocument, PrioriteOcr.REPRISE);
    }

    private Optional<StatutOcr> enfiler(UUID documentId, UUID versionId, UUID cleFichierId, String typeMime,
                                        String codeTypeDocument, PrioriteOcr priorite) {
        if (!actif || cleFichierId == null || !ExtracteurDocumentOcr.gere(typeMime)) return Optional.empty();
        file.enfiler(new OcrJobQueue.NouveauJob(documentId, versionId, cleFichierId, typeMime,
                langues.pour(codeTypeDocument), Instant.now(), priorite));
        return Optional.of(StatutOcr.EN_ATTENTE_OCR);
    }

    /** État OCR de chaque version demandée (absent : jamais enfilée). */
    public Map<UUID, StatutOcr> statuts(List<UUID> versionIds) {
        return versionIds.isEmpty() ? Map.of() : file.statutsParVersion(versionIds);
    }

    public boolean actif() {
        return actif;
    }
}
