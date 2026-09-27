package com.ipt.ged.support;

import java.nio.charset.StandardCharsets;

/**
 * Contenus de test reconnus comme PDF par leur signature ({@code %PDF-}).
 *
 * <p>Depuis le branchement du contrôle du type réel (§6.1.5, Tika), un fichier
 * nommé {@code .pdf} mais contenant « x » est du texte brut : refusé (415) par
 * un type documentaire qui n'admet que le PDF. Les tests d'API déposent donc
 * de vrais en-têtes PDF.
 */
public final class Pdfs {

    private Pdfs() {}

    public static byte[] pdf() {
        return pdf("contenu");
    }

    /** PDF minimal portant un marqueur, pour distinguer deux fichiers. */
    public static byte[] pdf(String marqueur) {
        return ("%PDF-1.4\n% " + marqueur + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }
}
