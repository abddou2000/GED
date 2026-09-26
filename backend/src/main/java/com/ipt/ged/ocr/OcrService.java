package com.ipt.ged.ocr;

import com.ipt.ged.document.StorageService;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Chaîne d'OCRisation.
 *
 * <p>Les extracteurs sont essayés dans l'ordre de leur priorité : d'abord la
 * lecture d'une couche texte, gratuite et sûre ; ensuite seulement la
 * reconnaissance optique, lente et faillible. Le premier qui produit un résultat
 * exploitable l'emporte.
 *
 * <p>Aucun moteur n'est nommé ici : ajouter un fournisseur revient à déposer une
 * implémentation de {@link ExtracteurTexte} dans le contexte Spring.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final List<ExtracteurTexte> extracteurs;
    private final UploadDocumentRepository documents;
    private final StorageService storage;
    private final boolean actif;

    public OcrService(List<ExtracteurTexte> extracteurs,
                      UploadDocumentRepository documents,
                      StorageService storage,
                      @Value("${ged.ocr.enabled:true}") boolean actif) {
        this.extracteurs = extracteurs.stream()
                .sorted(Comparator.comparingInt(ExtracteurTexte::priorite))
                .toList();
        this.documents = documents;
        this.storage = storage;
        this.actif = actif;
    }

    /** L'OCRisation est-elle autorisée sur ce serveur ? (cf. {@code ged.ocr.enabled}) */
    public boolean estActif() { return actif; }

    /** Texte d'un document déjà déposé. */
    public TexteExtrait lire(UUID documentId) {
        UploadDocument doc = documents.findById(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
        if (doc.getFilePath() == null) return TexteExtrait.aucune("Aucun fichier associé à ce document.");
        return lire(storage.chemin(doc.getFilePath()), doc.getExtension());
    }

    /** Texte d'un fichier sur disque. */
    public TexteExtrait lire(Path fichier, String extension) {
        if (!actif) return TexteExtrait.aucune("L'OCRisation est désactivée (ged.ocr.enabled=false).");

        List<ExtracteurTexte> candidats = extracteurs.stream()
                .filter(e -> e.gere(extension))
                .toList();
        if (candidats.isEmpty()) {
            return TexteExtrait.aucune("Aucun extracteur ne sait traiter le format « " + extension + " ».");
        }

        String dernierDetail = null;
        for (ExtracteurTexte e : candidats) {
            if (!e.disponible()) {
                dernierDetail = e.nom() + " indisponible sur ce serveur.";
                continue;
            }
            TexteExtrait r = e.extraire(fichier);
            if (r.exploitable()) {
                log.debug("Texte obtenu via {} ({} caractères)", e.nom(), r.texte().length());
                return r;
            }
            dernierDetail = r.detail();
        }
        return TexteExtrait.aucune(dernierDetail != null ? dernierDetail : "Aucun texte exploitable.");
    }

    /** État de la chaîne, pour diagnostic d'exploitation. */
    public List<EtatExtracteur> diagnostic() {
        return extracteurs.stream()
                .map(e -> new EtatExtracteur(e.nom(), e.priorite(), e.disponible()))
                .toList();
    }

    public record EtatExtracteur(String nom, int priorite, boolean disponible) {}
}
