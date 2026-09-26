package com.ipt.ged.fichier.chiffrement;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Chiffre en flux au format {@link FormatChiffre} : la mémoire consommée est
 * celle d'un segment, quelle que soit la taille du fichier.
 *
 * <p>Un segment plein n'est chiffré qu'à l'arrivée de l'octet suivant : c'est
 * le seul moyen de savoir s'il est le dernier, information liée à son
 * authentification. {@link #close()} chiffre le dernier segment (vide si le
 * fichier l'est) puis ferme le flux sous-jacent.
 */
public class FluxChiffrant extends OutputStream {

    private final OutputStream sortie;
    private final SecretKey dek;
    private final UUID id;
    private final byte[] entete;
    private final byte[] ivBase;
    private final byte[] clair;
    private final byte[] chiffre;
    private final Cipher cipher;
    private int remplis;
    private long rang;
    private boolean ferme;

    public FluxChiffrant(OutputStream sortie, SecretKey dek, UUID id, int tailleSegment, SecureRandom aleatoire)
            throws IOException {
        if (tailleSegment < FormatChiffre.SEGMENT_MIN || tailleSegment > FormatChiffre.SEGMENT_MAX) {
            throw new IllegalArgumentException("Taille de segment hors bornes : " + tailleSegment);
        }
        this.sortie = sortie;
        this.dek = dek;
        this.id = id;
        this.ivBase = new byte[FormatChiffre.IV_OCTETS];
        aleatoire.nextBytes(ivBase);
        this.entete = FormatChiffre.entete(tailleSegment, ivBase);
        this.clair = new byte[tailleSegment];
        this.chiffre = new byte[tailleSegment + FormatChiffre.ETIQUETTE_OCTETS];
        try {
            this.cipher = Cipher.getInstance(FormatChiffre.TRANSFORMATION);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponible dans ce JDK", e);
        }
        sortie.write(entete);
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (ferme) throw new IOException("Flux chiffrant déjà fermé");
        while (len > 0) {
            if (remplis == clair.length) {
                chiffrerSegment(false);
            }
            int n = Math.min(len, clair.length - remplis);
            System.arraycopy(b, off, clair, remplis, n);
            remplis += n;
            off += n;
            len -= n;
        }
    }

    @Override
    public void flush() throws IOException {
        sortie.flush();
    }

    @Override
    public void close() throws IOException {
        if (ferme) return;
        ferme = true;
        try {
            chiffrerSegment(true);
            sortie.flush();
        } finally {
            Arrays.fill(clair, (byte) 0);
            sortie.close();
        }
    }

    private void chiffrerSegment(boolean dernier) throws IOException {
        try {
            cipher.init(Cipher.ENCRYPT_MODE, dek,
                    new GCMParameterSpec(FormatChiffre.ETIQUETTE_BITS, FormatChiffre.nonce(ivBase, rang, dernier)));
            cipher.updateAAD(FormatChiffre.aad(entete, id, rang, dernier));
            int n = cipher.doFinal(clair, 0, remplis, chiffre, 0);
            sortie.write(chiffre, 0, n);
        } catch (GeneralSecurityException e) {
            throw new IOException("Chiffrement du segment " + rang + " impossible", e);
        }
        rang++;
        remplis = 0;
    }
}
