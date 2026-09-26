package com.ipt.ged.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Fabrique d'identifiants UUID version 7 (RFC 9562, §5.7) : 48 bits
 * d'horodatage en millisecondes, puis de l'aléa.
 *
 * <h2>Pourquoi la version 7 plutôt que la version 4</h2>
 * <p>Le dossier technique impose des clés primaires UUID opaques (§12.1,
 * §5.3.2). Avec des UUID aléatoires (v4), deux habitudes du code tomberaient
 * sans bruit :
 * <ul>
 *   <li>l'ordre par défaut des listes (« le plus récent d'abord ») et celui des
 *       versions d'un document reposent sur {@code ORDER BY id} ; un UUID v4 le
 *       rendrait aléatoire ;</li>
 *   <li>un index B-tree sur une clé aléatoire se fragmente : chaque insertion
 *       tombe au hasard dans l'index au lieu d'en compléter la fin.</li>
 * </ul>
 * <p>PostgreSQL compare les {@code uuid} octet par octet : l'horodatage placé
 * en tête rend l'ordre des clés chronologique. L'identifiant reste opaque pour
 * l'appelant — rien ne l'autorise à en déduire quoi que ce soit.
 *
 * <h2>Monotonie</h2>
 * <p>Plusieurs identifiants émis dans la même milliseconde (un dépôt crée le
 * document, sa version et ses demandes de signature dans la même transaction)
 * doivent rester dans l'ordre d'émission. La méthode 1 de la RFC (§6.2) est
 * appliquée : les 12 bits {@code rand_a} servent de compteur dans la
 * milliseconde ; s'il déborde, l'horodatage est avancé d'une milliseconde.
 * La garantie vaut au sein d'une JVM, ce qui suffit à l'ordre d'affichage.
 */
public final class UuidV7 {

    private static final SecureRandom ALEA = new SecureRandom();

    private static long dernierInstant = -1L;
    private static int compteur = 0;

    private UuidV7() {}

    /** Nouvel identifiant, strictement supérieur au précédent émis par cette JVM. */
    public static synchronized UUID suivant() {
        long maintenant = System.currentTimeMillis();
        if (maintenant > dernierInstant) {
            dernierInstant = maintenant;
            // Point de départ tiré dans la moitié basse : laisse de la marge au
            // compteur tout en gardant de l'aléa dans rand_a.
            compteur = ALEA.nextInt(0x800);
        } else {
            compteur++;
            if (compteur > 0xFFF) {
                dernierInstant++;
                compteur = 0;
            }
        }
        return construire(dernierInstant, compteur, ALEA.nextLong());
    }

    /**
     * Assemble un UUID v7 à partir de ses trois champs. Exposé pour la reprise
     * des données et les tests, qui ont besoin d'un horodatage donné.
     */
    static UUID construire(long instantMs, int randA, long randB) {
        long poidsFort = ((instantMs & 0xFFFF_FFFF_FFFFL) << 16)
                | (0x7L << 12)                       // version 7
                | (randA & 0xFFFL);
        long poidsFaible = (randB & 0x3FFF_FFFF_FFFF_FFFFL)
                | 0x8000_0000_0000_0000L;            // variante RFC 9562 (10xx)
        return new UUID(poidsFort, poidsFaible);
    }
}
