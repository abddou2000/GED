package com.ipt.ged.common;

/**
 * Bornes de longueur des chaînes persistées.
 *
 * <p>Une colonne {@code varchar} Hibernate fait 255 caractères par défaut. Sans
 * contrôle applicatif, une chaîne plus longue n'était refusée que par la base,
 * sous la forme d'une {@code DataIntegrityViolationException} — donc d'un 500 nu
 * côté client, sans indication du champ fautif. Le contrôle est ramené ici, au
 * plus près de la saisie, pour que le refus nomme le champ et sa limite.
 *
 * <p>La constante est partagée plutôt que recopiée : le jour où une colonne est
 * élargie, il n'y a qu'un endroit à revoir, et surtout un seul endroit où lire
 * la valeur réellement appliquée.
 */
public final class Limites {

    private Limites() {}

    /** Longueur d'une colonne {@code varchar} par défaut sous Hibernate. */
    public static final int TEXTE = 255;

    /**
     * Refuse une valeur trop longue en nommant le champ et la limite.
     *
     * @param champ nom du champ tel que l'utilisateur le voit à l'écran
     */
    public static void controler(String valeur, String champ) {
        controler(valeur, champ, TEXTE);
    }

    public static void controler(String valeur, String champ, int max) {
        if (valeur != null && valeur.length() > max) {
            throw new IllegalArgumentException(
                    "Le champ « " + champ + " » ne peut pas dépasser " + max
                            + " caractères (reçu : " + valeur.length() + ").");
        }
    }
}
