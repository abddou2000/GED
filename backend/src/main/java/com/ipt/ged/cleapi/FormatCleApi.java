package com.ipt.ged.cleapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Format des clés d'API : {@code ged_<env>_<identifiant>_<secret>} (DAT §5.4).
 * <ul>
 *   <li>{@code env} : environnement émetteur ({@code dev}, {@code uat},
 *       {@code prod}) — une clé d'UAT ne s'utilise pas en production ;</li>
 *   <li>{@code identifiant} : 16 caractères hexadécimaux, public, sert à
 *       retrouver la clé sans parcourir les empreintes ;</li>
 *   <li>{@code secret} : 256 bits aléatoires ({@link SecureRandom}), 64
 *       caractères hexadécimaux. L'hexadécimal plutôt que le base64url : ce
 *       dernier contient « _ », le séparateur du format.</li>
 * </ul>
 */
public final class FormatCleApi {

    private static final Pattern FORME =
            Pattern.compile("^ged_([a-z]{2,16})_([0-9a-f]{16})_([0-9a-f]{64})$");
    private static final SecureRandom ALEA = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private FormatCleApi() {}

    /** Clé lue dans l'en-tête, découpée. */
    public record CleLue(String environnement, String identifiant, String secret) {}

    /** Clé fraîchement générée : la valeur complète n'existe qu'ici, le temps de l'afficher. */
    public record CleGeneree(String valeur, String identifiant, String empreinteSecret) {}

    public static CleGeneree generer(String environnement) {
        if (!environnement.matches("^[a-z]{2,16}$")) {
            throw new IllegalArgumentException("Environnement de clé invalide : " + environnement);
        }
        String identifiant = aleatoire(8);
        String secret = aleatoire(32);
        return new CleGeneree("ged_" + environnement + "_" + identifiant + "_" + secret, identifiant, empreinte(secret));
    }

    public static Optional<CleLue> lire(String valeur) {
        if (valeur == null) return Optional.empty();
        Matcher m = FORME.matcher(valeur.trim());
        if (!m.matches()) return Optional.empty();
        return Optional.of(new CleLue(m.group(1), m.group(2), m.group(3)));
    }

    /** SHA-256 hexadécimal : suffisant pour un secret de 256 bits aléatoires (aucun dictionnaire possible). */
    public static String empreinte(String secret) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparaison à temps constant de deux empreintes. */
    public static boolean memeEmpreinte(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static String aleatoire(int octets) {
        byte[] b = new byte[octets];
        ALEA.nextBytes(b);
        return HEX.formatHex(b);
    }
}
