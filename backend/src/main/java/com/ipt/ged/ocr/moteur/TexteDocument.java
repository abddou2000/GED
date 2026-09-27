package com.ipt.ged.ocr.moteur;

/**
 * Texte d'un document multi-pages agrégé en une <b>unité documentaire unique</b>
 * (§4.3.4, étape 4), prêt à être enregistré dans {@code document_texte}.
 *
 * @param pagesNatives pages dont la couche texte PDF a été retenue sans OCR ;
 * @param pagesOcr     pages rendues à 300 dpi et reconnues par le moteur.
 */
public record TexteDocument(String texte, int nbPages, int pagesNatives, int pagesOcr, Provenance provenance) {

    public enum Provenance {
        /** Couche texte PDF sur toutes les pages. */
        COUCHE_TEXTE,
        /** Reconnaissance optique sur toutes les pages. */
        OCR,
        /** PDF mêlant pages natives et pages scannées. */
        MIXTE,
        /** Texte natif d'un format bureautique ou texte brut. */
        NATIF
    }
}
