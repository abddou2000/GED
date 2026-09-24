package com.ipt.ged.ocr;

/**
 * Un mot reconnu, avec sa position sur la page.
 *
 * <p>Le texte à plat suffit à retrouver « Fournisseur : ACME », mais pas à lire
 * un tableau ni un formulaire où la valeur se trouve à droite de son étiquette
 * ou juste en dessous : dans un texte linéaire, ces deux cas deviennent
 * indiscernables. Les coordonnées lèvent l'ambiguïté.
 *
 * @param x      bord gauche, en pixels de l'image reconnue
 * @param y      bord haut
 * @param confiance indice de certitude du moteur, de 0 à 100
 * @param ligne  identifiant de ligne, unique par page
 */
public record MotOcr(String texte, int x, int y, int largeur, int hauteur,
                     double confiance, int page, int ligne) {

    /** Bord droit du mot. */
    public int droite() { return x + largeur; }

    /** Milieu vertical — sert à décider si deux mots sont sur la même bande. */
    public int centreY() { return y + hauteur / 2; }
}
