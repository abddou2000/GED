package com.ipt.ged.ocr.file;

/**
 * États d'un job de la table {@code ocr_job} (§4.3.4).
 *
 * <p>Le document est « non interrogeable » tant qu'aucun job n'est
 * {@link #OCR_TERMINE} pour sa version courante ; en {@link #OCR_ECHEC}, il
 * reste consultable et téléchargeable, et le motif est visible en supervision.
 */
public enum StatutOcr {
    /** Déposé, en attente d'un worker (ou d'une nouvelle tentative programmée). */
    EN_ATTENTE_OCR,
    /** Réservé par un worker, bail en cours. */
    EN_COURS_OCR,
    /** Texte enregistré et indexé : le document est interrogeable. */
    OCR_TERMINE,
    /** Tentatives épuisées ou échec définitif : relance manuelle possible. */
    OCR_ECHEC
}
