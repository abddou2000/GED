package com.ipt.ged.journalisation;

import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Identifiants de trace au format W3C Trace Context
 * ({@code traceparent: 00-<traceId 32 hex>-<parentId 16 hex>-<flags 2 hex>}).
 *
 * <p>Le format W3C est retenu parce qu'il est celui que parlent NGINX (module
 * OpenTelemetry), les passerelles et les applications tierces de MMED : une
 * requête qui arrive déjà tracée garde son {@code traceId}, ce qui permet de
 * suivre un dépôt du bureau d'ordre jusqu'au journal de la GED (DAT 7.2, 7.3).
 *
 * @param traceId identifiant de la requête de bout en bout, 32 caractères hex
 * @param spanId  identifiant du traitement dans la GED, 16 caractères hex
 */
public record TraceW3C(String traceId, String spanId) {

    private static final Pattern TRACEPARENT =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$");
    private static final String TRACE_NULLE = "0".repeat(32);
    private static final String PARENT_NUL = "0".repeat(16);
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Trace de la requête entrante : on reprend le {@code traceId} d'un
     * {@code traceparent} valide, sinon on en génère un. Le {@code spanId} est
     * toujours neuf : il désigne le traitement de la GED, pas celui de l'appelant.
     */
    public static TraceW3C depuisEntete(String traceparent) {
        String traceId = traceIdValide(traceparent);
        return new TraceW3C(traceId != null ? traceId : nouvelIdentifiant(16), nouvelIdentifiant(8));
    }

    /** Traitement enfant de la même trace (appel sortant, tâche de fond). */
    public TraceW3C enfant() {
        return new TraceW3C(traceId, nouveauSpanId());
    }

    /** Nouvel identifiant de traitement, 16 caractères hexadécimaux. */
    public static String nouveauSpanId() {
        return nouvelIdentifiant(8);
    }

    /** En-tête à poser sur un appel sortant pour propager la trace (DAT 7.3). */
    public String entete() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /**
     * Rejette tout en-tête qui ne respecte pas la spécification (version
     * {@code ff} interdite, identifiants nuls interdits) : un en-tête forgé ne
     * doit pas pouvoir injecter n'importe quoi dans les journaux.
     */
    static String traceIdValide(String traceparent) {
        if (traceparent == null) return null;
        var m = TRACEPARENT.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        String version = m.group(1);
        if ("ff".equals(version)) return null;
        // La version 00 n'admet aucun champ supplémentaire.
        if ("00".equals(version) && m.group(5) != null) return null;
        if (TRACE_NULLE.equals(m.group(2)) || PARENT_NUL.equals(m.group(3))) return null;
        return m.group(2);
    }

    /* Aléa non cryptographique : un identifiant de trace sert à corréler des
       journaux, il ne protège rien et n'a pas à coûter un SecureRandom. */
    private static String nouvelIdentifiant(int octets) {
        byte[] b = new byte[octets];
        String id;
        do {
            ThreadLocalRandom.current().nextBytes(b);
            id = HEX.formatHex(b);
        } while (id.chars().allMatch(c -> c == '0'));
        return id;
    }
}
