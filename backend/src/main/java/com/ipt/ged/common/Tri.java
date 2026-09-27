package com.ipt.ged.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Construction d'un {@link Pageable} trié à partir des paramètres d'URL.
 *
 * <p>Mutualisé plutôt que recopié dans chaque service : sans cela, chaque écran
 * corrigé introduirait sa propre variante et les listes ne se comporteraient pas
 * de la même façon d'un module à l'autre.
 *
 * <p>Le champ demandé est confronté à une liste blanche fournie par l'appelant :
 * une valeur libre atteindrait directement le schéma de la table et une colonne
 * inexistante ferait remonter une erreur SQL au client.
 */
public final class Tri {

    private Tri() {}

    /** Colonne de repli — la plus récente d'abord, ordre historique des listes. */
    private static final String DEFAUT = "id";

    /**
     * Plafond de taille de page.
     *
     * <p>Au-delà, une « page » n'en est plus une : {@code size=100000} ramène la
     * table entière, sérialise autant d'objets et fait tomber le serveur avant
     * l'écran. Le paramètre est plafonné plutôt que refusé — l'appelant obtient
     * une réponse utilisable, simplement plus courte que demandée.
     */
    public static final int TAILLE_MAX = 200;

    /** Taille appliquée quand l'appelant en demande une absurde (0, négative) : 50 (DAT §5.3.2). */
    private static final int TAILLE_DEFAUT = 50;

    /**
     * @param champsAutorises colonnes sur lesquelles le tri est permis
     * @param sortBy          champ demandé ; ignoré s'il n'est pas autorisé
     * @param sortDir         {@code asc} ou {@code desc} (défaut)
     */
    public static Pageable pageable(int page, int size, String sortBy, String sortDir,
                                    Set<String> champsAutorises) {
        return pageable(page, size, sortBy, sortDir, champsAutorises, Set.of());
    }

    /**
     * @param champsNumeriques colonnes à trier sans passer par {@code lower(...)}.
     *                         Appliquer l'insensibilité à la casse sur un nombre
     *                         force une conversion en texte, et « 9 » passerait
     *                         alors après « 10 ».
     */
    public static Pageable pageable(int page, int size, String sortBy, String sortDir,
                                    Set<String> champsAutorises, Set<String> champsNumeriques) {
        int taille = taille(size);
        int numero = numeroDePage(page, taille);

        String champ = sortBy == null ? "" : sortBy.trim();
        if (!champsAutorises.contains(champ)) {
            return PageRequest.of(numero, taille, Sort.by(Sort.Direction.DESC, DEFAUT));
        }
        Sort.Direction sens = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort.Order ordre = new Sort.Order(sens, champ);
        // Insensible à la casse pour le texte : sinon « Validation » précède
        // « validation », ce qui ne ressemble pas à un ordre alphabétique.
        // L'identifiant (UUID) n'est jamais du texte : PostgreSQL refuse
        // lower(uuid), là où MySQL et H2 convertissaient en silence.
        if (!champsNumeriques.contains(champ) && !DEFAUT.equals(champ)) {
            ordre = ordre.ignoreCase();
        }
        return PageRequest.of(numero, taille, Sort.by(ordre));
    }

    /** Taille demandée, ramenée dans les bornes exploitables. */
    private static int taille(int size) {
        if (size < 1) return TAILLE_DEFAUT;
        return Math.min(size, TAILLE_MAX);
    }

    /**
     * Numéro de page vérifié AVANT d'atteindre la couche de persistance.
     *
     * <p>L'offset d'une page est {@code page × size}. Spring Data le calcule en
     * {@code long} mais JPA ne sait le poser qu'en {@code int} : au-delà de
     * {@link Integer#MAX_VALUE} l'erreur naissait au fond du dépôt, était
     * traduite en {@code InvalidDataAccessApiUsageException} et ressortait en
     * <b>500</b>. Un numéro de page hors limites est une faute de l'appelant,
     * pas une panne du serveur : on le refuse ici, en 400, avec la borne.
     */
    private static int numeroDePage(int page, int taille) {
        if (page < 0) {
            throw new IllegalArgumentException("Le numéro de page ne peut pas être négatif (reçu : " + page + ").");
        }
        long offset = (long) page * taille;
        if (offset > Integer.MAX_VALUE) {
            long maxPage = Integer.MAX_VALUE / taille;
            throw new IllegalArgumentException("Numéro de page hors limites : au plus " + maxPage
                    + " pour une taille de page de " + taille + " (reçu : " + page + ").");
        }
        return page;
    }
}
