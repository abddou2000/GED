package com.ipt.ged.ocr;

import com.ipt.ged.document.DocumentVersionRepository;
import com.ipt.ged.ocr.file.EnfilageOcr;
import com.ipt.ged.ocr.file.StatutOcr;
import com.ipt.ged.ocr.moteur.LanguesOcr;
import com.ipt.ged.ocr.moteur.OcrEngine;
import com.ipt.ged.recherche.SearchIndexer;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chaîne OCR vue de l'application.
 *
 * <ul>
 *   <li>{@code GET /etat} — la chaîne asynchrone est-elle active, le moteur
 *       répond-il, avec quelles langues ;</li>
 *   <li>{@code GET /documents/{id}/texte} — texte extrait de la version
 *       courante et état de son traitement (« non interrogeable » tant qu'il
 *       n'est pas {@code OCR_TERMINE}).</li>
 * </ul>
 *
 * <p>La lecture synchrone du contenu (qui servait à pré-remplir les index) a
 * disparu avec le cloisonnement §4.3.3 : le texte ne sert qu'à la recherche.
 */
@RestController
@RequestMapping("/api/v1/ocr")
public class OcrController {

    private final EnfilageOcr enfilage;
    private final OcrEngine moteur;
    private final LanguesOcr langues;
    private final SearchIndexer indexer;
    private final DocumentVersionRepository versions;

    public OcrController(EnfilageOcr enfilage, OcrEngine moteur, LanguesOcr langues, SearchIndexer indexer,
                         DocumentVersionRepository versions) {
        this.enfilage = enfilage;
        this.moteur = moteur;
        this.langues = langues;
        this.indexer = indexer;
        this.versions = versions;
    }

    @GetMapping("/etat")
    public Etat etat() {
        return new Etat(enfilage.actif(), moteur.disponible(), moteur.languesInstallees(), langues.defaut());
    }

    @GetMapping("/documents/{id}/texte")
    @Transactional(readOnly = true)
    public Texte texte(@PathVariable UUID id) {
        var courante = versions.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(id).stream().findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
        StatutOcr statut = enfilage.statuts(List.of(courante.getId())).get(courante.getId());
        String st = statut != null ? statut.name() : null;
        return indexer.texte(id)
                .filter(t -> t.versionId().equals(courante.getId()))
                .map(t -> new Texte(id, courante.getId(), st, true, t.provenance(), t.nbPages(), t.langue(),
                        t.indexeLe(), t.texte()))
                .orElseGet(() -> new Texte(id, courante.getId(), st, false, null, null, null, null, null));
    }

    /**
     * @param actif            chaîne asynchrone active (workers, enfilage au dépôt) ;
     * @param moteurDisponible le binaire Tesseract répond.
     */
    public record Etat(boolean actif, boolean moteurDisponible, Set<String> languesInstallees, String langueDefaut) {}

    /** @param interrogeable le texte de la version courante est dans l'index plein texte. */
    public record Texte(UUID documentId, UUID versionId, String statutOcr, boolean interrogeable, String provenance,
                        Integer nbPages, String langue, Instant indexeLe, String texte) {}
}
