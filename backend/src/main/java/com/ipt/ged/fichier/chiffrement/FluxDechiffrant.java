package com.ipt.ged.fichier.chiffrement;

import com.ipt.ged.fichier.Refus;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Déchiffre en flux un fichier au format {@link FormatChiffre}.
 *
 * <p>Chaque segment est authentifié <b>avant</b> que le moindre de ses octets
 * soit rendu. Une altération lève une {@link com.ipt.ged.fichier.ErreurFichierException}
 * {@code INTEGRITE_COMPROMISE} : le lecteur n'a alors reçu que des segments
 * intègres, jamais une donnée falsifiée.
 */
public class FluxDechiffrant extends InputStream {

    private final InputStream entree;
    private final SecretKey dek;
    private final UUID id;
    private final byte[] entete;
    private final byte[] ivBase;
    private final byte[] chiffre;
    private final byte[] clair;
    private final Cipher cipher;
    private int disponibles;
    private int position;
    private long rang;
    private boolean termine;
    /** Octet lu d'avance pour savoir si le segment courant est le dernier ; -1 = aucun. */
    private int avance = -1;

    public FluxDechiffrant(InputStream entree, SecretKey dek, UUID id) throws IOException {
        this.entree = entree;
        this.dek = dek;
        this.id = id;
        this.entete = entree.readNBytes(FormatChiffre.ENTETE_OCTETS);
        if (entete.length < FormatChiffre.ENTETE_OCTETS
                || !Arrays.equals(entete, 0, 4, FormatChiffre.MAGIQUE, 0, 4)) {
            throw Refus.integriteCompromise("en-tête absent ou inconnu", null);
        }
        ByteBuffer b = ByteBuffer.wrap(entete, 4, FormatChiffre.ENTETE_OCTETS - 4);
        byte version = b.get();
        int tailleSegment = b.getInt();
        if (version != FormatChiffre.VERSION) {
            throw Refus.integriteCompromise("version de format " + version + " non prise en charge", null);
        }
        if (tailleSegment < FormatChiffre.SEGMENT_MIN || tailleSegment > FormatChiffre.SEGMENT_MAX) {
            throw Refus.integriteCompromise("taille de segment invalide", null);
        }
        this.ivBase = new byte[FormatChiffre.IV_OCTETS];
        b.get(ivBase);
        this.chiffre = new byte[tailleSegment + FormatChiffre.ETIQUETTE_OCTETS];
        this.clair = new byte[tailleSegment];
        try {
            this.cipher = Cipher.getInstance(FormatChiffre.TRANSFORMATION);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM indisponible dans ce JDK", e);
        }
    }

    /** Taille de segment lue dans l'en-tête. */
    public static int tailleSegment(byte[] entete) {
        return ByteBuffer.wrap(entete, 5, 4).getInt();
    }

    @Override
    public int read() throws IOException {
        byte[] un = new byte[1];
        int n = read(un, 0, 1);
        return n < 0 ? -1 : un[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (position == disponibles) {
            if (termine) return -1;
            dechiffrerSegment();
        }
        int n = Math.min(len, disponibles - position);
        System.arraycopy(clair, position, b, off, n);
        position += n;
        return n;
    }

    @Override
    public int available() {
        return disponibles - position;
    }

    @Override
    public void close() throws IOException {
        Arrays.fill(clair, (byte) 0);
        entree.close();
    }

    private void dechiffrerSegment() throws IOException {
        int lus = 0;
        if (avance >= 0) {
            chiffre[0] = (byte) avance;
            lus = 1;
            avance = -1;
        }
        lus += entree.readNBytes(chiffre, lus, chiffre.length - lus);
        boolean dernier;
        if (lus < chiffre.length) {
            dernier = true;
        } else {
            int suivant = entree.read();
            dernier = suivant < 0;
            avance = suivant;
        }
        if (lus < FormatChiffre.ETIQUETTE_OCTETS) {
            throw Refus.integriteCompromise("fichier tronqué au segment " + rang, null);
        }
        try {
            cipher.init(Cipher.DECRYPT_MODE, dek,
                    new GCMParameterSpec(FormatChiffre.ETIQUETTE_BITS, FormatChiffre.nonce(ivBase, rang, dernier)));
            cipher.updateAAD(FormatChiffre.aad(entete, id, rang, dernier));
            disponibles = cipher.doFinal(chiffre, 0, lus, clair, 0);
        } catch (AEADBadTagException e) {
            throw Refus.integriteCompromise("authentification GCM en échec au segment " + rang, e);
        } catch (GeneralSecurityException e) {
            throw new IOException("Déchiffrement du segment " + rang + " impossible", e);
        }
        position = 0;
        rang++;
        termine = dernier;
    }
}
