package com.ipt.ged.fichier.previsualisation;

import java.nio.file.Path;

/**
 * Conversion d'un document bureautique en PDF pour la prévisualisation
 * (§6.1.6). Implémentation livrée : LibreOffice sans interface ; l'interface
 * permet de brancher un service de conversion distant sans toucher au reste.
 */
public interface ConvertisseurBureautique {

    /** Le moteur de conversion est-il installé et opérationnel ? */
    boolean disponible();

    /**
     * Convertit {@code source} en PDF dans {@code dossierSortie}.
     *
     * @return chemin du PDF produit.
     * @throws com.ipt.ged.fichier.ErreurFichierException 503 si la conversion échoue.
     */
    Path convertirEnPdf(Path source, Path dossierSortie);
}
