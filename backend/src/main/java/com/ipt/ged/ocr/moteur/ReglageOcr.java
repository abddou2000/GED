package com.ipt.ged.ocr.moteur;

/**
 * Réglage OCR réellement employé par cette instance (P-14, R30), journalisé au
 * démarrage et rendu par {@code GET /api/v1/ocr/etat}.
 *
 * @param modeles  {@code entiers} (copie compactée par {@code combine_tessdata},
 *                 {@link ModelesEntiers}), {@code precis} (modèles livrés,
 *                 {@code ged.ocr.modeles=precis}), {@code repli} (entiers demandés,
 *                 aucun modèle compacté : modèles livrés, débit réduit) ou
 *                 {@code installation} ({@code ged.ocr.tessdata} vide : modèles de
 *                 l'installation de Tesseract) ;
 * @param tessdata répertoire passé à Tesseract (vide : celui de l'installation) ;
 * @param dpi      résolution du rendu des pages PDF scannées.
 */
public record ReglageOcr(String modeles, String tessdata, int dpi) {
}
