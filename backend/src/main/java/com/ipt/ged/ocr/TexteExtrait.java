package com.ipt.ged.ocr;

/**
 * Texte tiré d'un document, avec la façon dont il a été obtenu.
 *
 * <p>La provenance compte autant que le texte : une couche texte native est
 * fiable, un OCR sur scan ne l'est pas au même degré. L'opérateur doit pouvoir
 * en tenir compte avant de valider une indexation.
 */
public record TexteExtrait(String texte, Provenance provenance, int nbPages, String detail) {

    public enum Provenance {
        /** Couche texte du PDF, lue telle quelle — fiable. */
        COUCHE_TEXTE,
        /** Reconnaissance optique sur une image ou un scan — à relire. */
        OCR,
        /** Rien n'a pu être lu. */
        AUCUNE
    }

    public static TexteExtrait aucune(String detail) {
        return new TexteExtrait("", Provenance.AUCUNE, 0, detail);
    }

    /** Vrai si l'extraction a produit quelque chose d'exploitable. */
    public boolean exploitable() {
        return provenance != Provenance.AUCUNE && texte != null && !texte.isBlank();
    }
}
