package com.ipt.ged.corpus;

/**
 * Types de documents reçus au bureau d'ordre, avec leur poids dans chaque jeu
 * et leur nombre de pages typique.
 *
 * <p>Poids T-028 : répartition des 300 pages. Poids P-14 : répartition des
 * documents (les contrats, plus longs, pèsent davantage en pages).
 */
enum TypeDoc {
    COURRIER("courrier", "Courrier entrant", 20, 24, 1, 3),
    FACTURE("facture", "Facture", 14, 16, 1, 2),
    BON_COMMANDE("bon-commande", "Bon de commande", 9, 9, 1, 2),
    CONTRAT("contrat", "Contrat", 12, 5, 3, 16),
    PROCES_VERBAL("proces-verbal", "Procès-verbal", 9, 6, 2, 5),
    ATTESTATION("attestation", "Attestation", 8, 12, 1, 1),
    NOTE_SERVICE("note-service", "Note de service", 8, 9, 1, 2),
    BORDEREAU("bordereau", "Bordereau d'envoi", 9, 9, 1, 2),
    FORMULAIRE("formulaire", "Demande manuscrite", 11, 10, 1, 2);

    final String code;
    final String libelle;
    final int poidsT028;
    final int poidsP14;
    final int pagesMin;
    final int pagesMax;

    TypeDoc(String code, String libelle, int poidsT028, int poidsP14, int pagesMin, int pagesMax) {
        this.code = code;
        this.libelle = libelle;
        this.poidsT028 = poidsT028;
        this.poidsP14 = poidsP14;
        this.pagesMin = pagesMin;
        this.pagesMax = pagesMax;
    }

    /** Nombre de pages d'un document, biaisé vers les documents courts. */
    int tirerPages(java.util.Random r) {
        double u = r.nextDouble();
        return pagesMin + (int) Math.floor((pagesMax - pagesMin + 1) * u * u);
    }
}
