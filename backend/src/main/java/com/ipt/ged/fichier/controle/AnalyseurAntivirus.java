package com.ipt.ged.fichier.controle;

/**
 * Analyse antivirus d'un fichier avant son écriture définitive (§6.1.5).
 *
 * <p>Contrat d'<b>échec fermé</b> : une implémentation ne rend normalement la
 * main que si le fichier est sain. Infecté → {@code ErreurFichierException}
 * 422 {@code FICHIER_INFECTE} ; moteur injoignable, en erreur ou réponse
 * incompréhensible → 503 {@code ANTIVIRUS_INDISPONIBLE}. Il n'existe aucun
 * chemin où un doute laisse passer le fichier.
 */
public interface AnalyseurAntivirus {

    /**
     * @return la réponse du moteur pour un fichier sain (journalisable).
     */
    String analyser(SourceFichier source);

    /** Le moteur répond-il ? (sonde d'exploitation, sans valeur de décision). */
    boolean disponible();
}
