package com.ipt.ged.ocr;

import java.nio.file.Path;

/**
 * Port d'extraction de texte. L'architecture ne s'engage sur aucun moteur :
 * chaque implémentation déclare ce qu'elle sait traiter, et
 * {@link OcrService} les enchaîne du plus fiable au plus coûteux.
 *
 * <p>Ajouter un fournisseur (Tesseract local, service cloud, moteur maison)
 * consiste à écrire une implémentation de plus — rien d'autre ne bouge.
 */
public interface ExtracteurTexte {

    /** Nom court affiché dans les diagnostics. */
    String nom();

    /** Ce fournisseur est-il utilisable ici et maintenant (binaire présent, clé configurée…) ? */
    boolean disponible();

    /** Sait-il traiter cette extension ? */
    boolean gere(String extension);

    /**
     * Ordre d'essai : plus la valeur est basse, plus tôt le fournisseur est tenté.
     * La lecture d'une couche texte passe avant un OCR, moins sûr et plus lent.
     */
    int priorite();

    TexteExtrait extraire(Path fichier);
}
