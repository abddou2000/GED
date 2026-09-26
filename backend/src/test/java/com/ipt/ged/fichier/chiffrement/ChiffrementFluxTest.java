package com.ipt.ged.fichier.chiffrement;

import com.ipt.ged.fichier.ErreurFichierException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.2 — AES-256-GCM segmenté : aller-retour, et toute altération détectée
 * (octet modifié, troncature, prolongement, permutation, recopie sous un
 * autre identifiant, en-tête falsifié).
 */
class ChiffrementFluxTest {

    private static final int SEGMENT = 1024;
    private static final SecureRandom ALEA = new SecureRandom();

    private static SecretKey cle() throws Exception {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }

    private static byte[] chiffrer(byte[] clair, SecretKey k, UUID id, int segment) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FluxChiffrant c = new FluxChiffrant(out, k, id, segment, ALEA)) {
            c.write(clair);
        }
        return out.toByteArray();
    }

    private static byte[] dechiffrer(byte[] chiffre, SecretKey k, UUID id) throws IOException {
        try (InputStream in = new FluxDechiffrant(new ByteArrayInputStream(chiffre), k, id)) {
            return in.readAllBytes();
        }
    }

    private static byte[] aleatoire(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @ParameterizedTest(name = "{0} octets")
    @ValueSource(ints = {0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 2 * SEGMENT, 3 * SEGMENT + 17})
    @DisplayName("Aller-retour exact, y compris aux frontières de segment")
    void allerRetour(int taille) throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] clair = aleatoire(taille);
        byte[] chiffre = chiffrer(clair, k, id, SEGMENT);
        assertArrayEquals(clair, dechiffrer(chiffre, k, id));
        assertEquals(taille, FormatChiffre.tailleEnClair(chiffre.length, SEGMENT));
    }

    @Test
    @DisplayName("Le chiffré ne contient pas le clair et commence par l'en-tête versionné")
    void enteteEtOpacite() throws Exception {
        byte[] clair = "CONFIDENTIEL MARCHICA ".repeat(200).getBytes();
        byte[] chiffre = chiffrer(clair, cle(), UUID.randomUUID(), SEGMENT);
        assertArrayEquals(new byte[]{'G', 'E', 'D', 'C', 1}, Arrays.copyOf(chiffre, 5));
        assertFalse(new String(chiffre, java.nio.charset.StandardCharsets.ISO_8859_1).contains("CONFIDENTIEL"));
    }

    @Test
    @DisplayName("Deux chiffrements du même contenu diffèrent (IV aléatoire par fichier)")
    void ivUnique() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] clair = aleatoire(3000);
        assertFalse(Arrays.equals(chiffrer(clair, k, id, SEGMENT), chiffrer(clair, k, id, SEGMENT)));
    }

    @Test
    @DisplayName("Nonces distincts pour chaque segment et pour l'indicateur « dernier »")
    void noncesDistincts() {
        byte[] iv = new byte[12];
        ALEA.nextBytes(iv);
        Set<String> vus = new HashSet<>();
        for (long r = 0; r < 5000; r++) {
            assertTrue(vus.add(Arrays.toString(FormatChiffre.nonce(iv, r, false))));
            assertTrue(vus.add(Arrays.toString(FormatChiffre.nonce(iv, r, true))));
        }
    }

    @Test
    @DisplayName("Un octet modifié dans un segment est détecté par GCM")
    void octetAltere() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(3 * SEGMENT), k, id, SEGMENT);
        chiffre[FormatChiffre.ENTETE_OCTETS + SEGMENT + 40] ^= 1; // 2e segment
        assertIntegriteCompromise(() -> dechiffrer(chiffre, k, id));
    }

    @Test
    @DisplayName("Aucun octet d'un segment altéré n'est rendu au lecteur")
    void rienDeFalsifieNestRendu() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] clair = aleatoire(3 * SEGMENT);
        byte[] chiffre = chiffrer(clair, k, id, SEGMENT);
        chiffre[FormatChiffre.ENTETE_OCTETS + (SEGMENT + 16) + 5] ^= 1; // 2e segment
        ByteArrayOutputStream recu = new ByteArrayOutputStream();
        try (InputStream in = new FluxDechiffrant(new ByteArrayInputStream(chiffre), k, id)) {
            assertThrows(ErreurFichierException.class, () -> in.transferTo(recu));
        }
        // Seul le premier segment, intègre, a pu être livré.
        assertArrayEquals(Arrays.copyOf(clair, SEGMENT), recu.toByteArray());
    }

    @Test
    @DisplayName("Troncature à une frontière de segment détectée (indicateur « dernier »)")
    void troncatureAFrontiere() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(3 * SEGMENT + 10), k, id, SEGMENT);
        byte[] tronque = Arrays.copyOf(chiffre, FormatChiffre.ENTETE_OCTETS + 2 * (SEGMENT + 16));
        assertIntegriteCompromise(() -> dechiffrer(tronque, k, id));
    }

    @Test
    @DisplayName("Troncature en cours de segment, ou fichier réduit à l'en-tête, détectées")
    void troncatureAuMilieu() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(2 * SEGMENT), k, id, SEGMENT);
        assertIntegriteCompromise(() -> dechiffrer(Arrays.copyOf(chiffre, chiffre.length - 7), k, id));
        assertIntegriteCompromise(() -> dechiffrer(Arrays.copyOf(chiffre, FormatChiffre.ENTETE_OCTETS), k, id));
        assertIntegriteCompromise(() -> dechiffrer(Arrays.copyOf(chiffre, 10), k, id));
    }

    @Test
    @DisplayName("Octets ajoutés en fin de fichier détectés")
    void prolongement() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(SEGMENT + 100), k, id, SEGMENT);
        byte[] prolonge = Arrays.copyOf(chiffre, chiffre.length + 32);
        assertIntegriteCompromise(() -> dechiffrer(prolonge, k, id));
    }

    @Test
    @DisplayName("Segments permutés détectés")
    void permutation() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(3 * SEGMENT), k, id, SEGMENT);
        int s = SEGMENT + 16, e = FormatChiffre.ENTETE_OCTETS;
        byte[] permute = chiffre.clone();
        System.arraycopy(chiffre, e, permute, e + s, s);
        System.arraycopy(chiffre, e + s, permute, e, s);
        assertIntegriteCompromise(() -> dechiffrer(permute, k, id));
    }

    @Test
    @DisplayName("Fichier recopié sous un autre identifiant : refusé")
    void autreIdentifiant() throws Exception {
        SecretKey k = cle();
        byte[] chiffre = chiffrer(aleatoire(500), k, UUID.randomUUID(), SEGMENT);
        assertIntegriteCompromise(() -> dechiffrer(chiffre, k, UUID.randomUUID()));
    }

    @Test
    @DisplayName("En-tête falsifié (taille de segment, version, magique) refusé")
    void enteteFalsifie() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(2000), k, id, SEGMENT);
        byte[] magique = chiffre.clone();
        magique[0] = 'X';
        assertIntegriteCompromise(() -> dechiffrer(magique, k, id));
        byte[] version = chiffre.clone();
        version[4] = 9;
        assertIntegriteCompromise(() -> dechiffrer(version, k, id));
        byte[] segment = chiffre.clone();
        segment[5] = 0x7F; // taille de segment démesurée
        assertIntegriteCompromise(() -> dechiffrer(segment, k, id));
        byte[] iv = chiffre.clone();
        iv[FormatChiffre.ENTETE_OCTETS - 1] ^= 1;
        assertIntegriteCompromise(() -> dechiffrer(iv, k, id));
    }

    @Test
    @DisplayName("Mauvaise clé : refusé")
    void mauvaiseCle() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] chiffre = chiffrer(aleatoire(100), cle(), id, SEGMENT);
        assertIntegriteCompromise(() -> dechiffrer(chiffre, cle(), id));
    }

    @Test
    @DisplayName("Flux de 64 Mo chiffré puis déchiffré en flux, sans tampon de fichier entier")
    void grosFluxEnContinu() throws Exception {
        SecretKey k = cle();
        UUID id = UUID.randomUUID();
        long taille = 64L * 1024 * 1024;
        MessageDigest avant = MessageDigest.getInstance("SHA-256");
        MessageDigest apres = MessageDigest.getInstance("SHA-256");
        // Le chiffré transite par un tube : ni le clair ni le chiffré complets
        // n'existent jamais en mémoire.
        java.io.PipedInputStream tube = new java.io.PipedInputStream(256 * 1024);
        java.io.PipedOutputStream entreeTube = new java.io.PipedOutputStream(tube);
        Thread producteur = new Thread(() -> {
            try (FluxChiffrant c = new FluxChiffrant(entreeTube, k, id, FormatChiffre.SEGMENT_PAR_DEFAUT, ALEA)) {
                byte[] bloc = new byte[100_000];
                long restant = taille;
                Random r = new Random(1);
                while (restant > 0) {
                    int n = (int) Math.min(bloc.length, restant);
                    r.nextBytes(bloc);
                    avant.update(bloc, 0, n);
                    c.write(bloc, 0, n);
                    restant -= n;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        producteur.start();
        long lus = 0;
        try (InputStream in = new FluxDechiffrant(tube, k, id)) {
            byte[] b = new byte[70_000];
            int n;
            while ((n = in.read(b)) >= 0) {
                apres.update(b, 0, n);
                lus += n;
            }
        }
        producteur.join();
        assertEquals(taille, lus);
        assertArrayEquals(avant.digest(), apres.digest());
    }

    @Test
    @DisplayName("Le flux chiffrant écrit l'en-tête puis ferme le flux sous-jacent")
    void fermeture() throws Exception {
        boolean[] ferme = {false};
        OutputStream sous = new ByteArrayOutputStream() {
            @Override public void close() { ferme[0] = true; }
        };
        FluxChiffrant c = new FluxChiffrant(sous, cle(), UUID.randomUUID(), SEGMENT, ALEA);
        c.close();
        c.close(); // idempotent
        assertTrue(ferme[0]);
        assertThrows(IOException.class, () -> c.write(1));
    }

    private static void assertIntegriteCompromise(org.junit.jupiter.api.function.Executable e) {
        ErreurFichierException ex = assertThrows(ErreurFichierException.class, e);
        assertEquals("INTEGRITE_COMPROMISE", ex.code());
        assertEquals(500, ex.statut().value());
    }
}
