package com.ipt.ged.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Étage 1 de l'OCRisation : lecture de la couche texte des PDF natifs.
 *
 * <p>Un PDF produit par un logiciel porte déjà son texte ; le reconnaître par
 * OCR serait à la fois plus lent et moins fiable. Cet extracteur n'exige aucune
 * installation sur le serveur, contrairement à {@link ExtracteurTesseract}.
 *
 * <p>Un PDF issu d'un scanner ne contient qu'une image : la lecture renvoie
 * alors une chaîne vide, et {@link OcrService} passe la main à l'étage suivant.
 */
@Component
public class ExtracteurPdfNatif implements ExtracteurTexte {

    private static final Logger log = LoggerFactory.getLogger(ExtracteurPdfNatif.class);

    /** En deçà, on considère qu'il n'y a pas de vraie couche texte (PDF scanné). */
    private static final int SEUIL_CARACTERES = 12;

    @Override public String nom() { return "PDF natif (PDFBox)"; }
    @Override public boolean disponible() { return true; }
    @Override public boolean gere(String extension) { return "pdf".equalsIgnoreCase(extension); }
    @Override public int priorite() { return 10; }

    @Override
    public TexteExtrait extraire(Path fichier) {
        try (PDDocument doc = Loader.loadPDF(fichier.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String texte = stripper.getText(doc);
            int pages = doc.getNumberOfPages();

            if (texte == null || texte.strip().length() < SEUIL_CARACTERES) {
                return new TexteExtrait("", TexteExtrait.Provenance.AUCUNE, pages,
                        "Aucune couche texte : ce PDF est probablement un scan, un OCR est nécessaire.");
            }
            return new TexteExtrait(texte.strip(), TexteExtrait.Provenance.COUCHE_TEXTE, pages,
                    "Couche texte lue sur " + pages + " page(s).");
        } catch (Exception e) {
            log.warn("Lecture PDF impossible : {}", fichier, e);
            return TexteExtrait.aucune("PDF illisible : " + e.getMessage());
        }
    }
}
