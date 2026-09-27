package com.ipt.ged.cycledevie.conservation;

import java.nio.file.Path;

/**
 * Validation d'un fichier contre le profil PDF/A-2B (ISO 19005-2, niveau B) :
 * c'est le contrôle qui décide si une copie de conservation est acceptée
 * (§6.1.4). Implémentation livrée : veraPDF, en bibliothèque.
 */
public interface ValidateurPdfA {

    Validation valider(Path pdf);

    /**
     * @param conforme vrai si aucune règle du profil n'est enfreinte ;
     * @param detail   règles enfreintes (clause et message), ou cause de
     *                 l'impossibilité de valider ; vide si conforme.
     */
    record Validation(boolean conforme, String detail) {
    }
}
