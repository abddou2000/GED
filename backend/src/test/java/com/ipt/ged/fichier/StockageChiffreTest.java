package com.ipt.ged.fichier;

import com.ipt.ged.fichier.cles.CleFichier;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.cles.RotationKek;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.1 à §6.1.4 — stockage chiffré de bout en bout : DEK par fichier,
 * empreinte du clair, rotation par réenveloppement, destruction
 * cryptographique.
 */
class StockageChiffreTest {

    @TempDir Path dossier;

    private FileStoreDisque store;
    private KeystoreKeyProvider keyProvider;
    private DepotClesFichierMemoire cles;
    private StockageChiffre stockage;

    @BeforeEach
    void preparer() {
        store = new FileStoreDisque(dossier.resolve("coffre"));
        keyProvider = new KeystoreKeyProvider(dossier.resolve("cles/kek.p12"), "mdp".toCharArray(), true);
        cles = new DepotClesFichierMemoire();
        stockage = new StockageChiffre(store, keyProvider, cles, 4096);
    }

    private static byte[] contenu(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private byte[] lire(UUID id) throws IOException {
        try (InputStream in = stockage.lire(id)) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("Aller-retour ; empreinte = SHA-256 du clair ; DEK enveloppée enregistrée")
    void allerRetour() throws Exception {
        byte[] clair = contenu(10_000);
        StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(clair), 1_000_000);
        assertEquals(sha256(clair), r.empreinte());
        assertEquals(10_000, r.tailleOctets());
        assertEquals(keyProvider.kekActive(), r.kekId());
        assertArrayEquals(clair, lire(r.id()));
        assertEquals(10_000, stockage.tailleEnClair(r.id()));
        CleFichier cle = cles.trouver(r.id()).orElseThrow();
        assertEquals("AES-256-GCM", cle.algorithme());
        assertEquals(60, cle.dekEnveloppee().length);
    }

    @Test
    @DisplayName("Aucun fichier en clair sur le disque : le contenu n'apparaît pas dans le référentiel")
    void aucunClairSurDisque() throws Exception {
        byte[] clair = "Rapport strictement confidentiel de Marchica Med".repeat(50).getBytes(StandardCharsets.UTF_8);
        StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(clair), 1_000_000);
        try (Stream<Path> tout = Files.walk(dossier.resolve("coffre"))) {
            for (Path p : tout.filter(Files::isRegularFile).toList()) {
                assertTrue(p.getFileName().toString().endsWith(".enc"));
                assertFalse(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains("confidentiel"));
            }
        }
        assertTrue(Files.exists(store.chemin(r.id())));
    }

    @Test
    @DisplayName("Une DEK différente par fichier (clé par version)")
    void dekParFichier() {
        UUID a = stockage.ecrire(new ByteArrayInputStream(contenu(10)), 100).id();
        UUID b = stockage.ecrire(new ByteArrayInputStream(contenu(10)), 100).id();
        assertNotEquals(a, b);
        byte[] ea = cles.trouver(a).orElseThrow().dekEnveloppee();
        byte[] eb = cles.trouver(b).orElseThrow().dekEnveloppee();
        assertFalse(java.util.Arrays.equals(ea, eb));
    }

    @Test
    @DisplayName("Fichier altéré sur le disque : lecture refusée (GCM)")
    void alterationDetectee() throws Exception {
        StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(contenu(9000)), 1_000_000);
        try (RandomAccessFile f = new RandomAccessFile(store.chemin(r.id()).toFile(), "rw")) {
            f.seek(100);
            int b = f.read();
            f.seek(100);
            f.write(b ^ 0x01);
        }
        ErreurFichierException e = assertThrows(ErreurFichierException.class, () -> lire(r.id()));
        assertEquals("INTEGRITE_COMPROMISE", e.code());
    }

    @Test
    @DisplayName("Dépassement de taille pendant le flux → 413, rien n'est publié")
    void tailleDepasseeEnFlux() {
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> stockage.ecrire(new ByteArrayInputStream(contenu(5000)), 4999));
        assertEquals(413, e.statut().value());
        assertEquals(0, cles.taille());
        List<UUID> publies = new ArrayList<>();
        assertDoesNotThrow(() -> store.parcourir(publies::add));
        assertTrue(publies.isEmpty());
    }

    @Test
    @DisplayName("Échec d'enregistrement de la clé : le fichier publié est retiré")
    void compensationSiCleNonEnregistree() throws Exception {
        cles.echouerAEnregistrement = true;
        assertThrows(IllegalStateException.class, () -> stockage.ecrire(new ByteArrayInputStream(contenu(10)), 100));
        List<UUID> publies = new ArrayList<>();
        store.parcourir(publies::add);
        assertTrue(publies.isEmpty());
    }

    @Test
    @DisplayName("Destruction cryptographique : sans sa DEK, le fichier est illisible")
    void destructionCryptographique() throws Exception {
        StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(contenu(500)), 1000);
        byte[] chiffre = Files.readAllBytes(store.chemin(r.id()));
        // Seule la DEK est détruite : le fichier (ou sa copie en sauvegarde) reste.
        assertTrue(cles.supprimer(r.id()));
        assertTrue(Files.exists(store.chemin(r.id())));
        ErreurFichierException e = assertThrows(ErreurFichierException.class, () -> stockage.lire(r.id()));
        assertEquals("FICHIER_INTROUVABLE", e.code());
        // Réinjecter une autre DEK (celle d'un autre fichier) ne rend pas le fichier lisible.
        StockageChiffre.ResultatStockage autre = stockage.ecrire(new ByteArrayInputStream(contenu(500)), 1000);
        CleFichier cleAutre = cles.trouver(autre.id()).orElseThrow();
        cles.enregistrer(new CleFichier(r.id(), cleAutre.dekEnveloppee(), cleAutre.kekId(), cleAutre.algorithme()));
        assertEquals("INTEGRITE_COMPROMISE", assertThrows(ErreurFichierException.class, () -> lire(r.id())).code());
        assertArrayEquals(chiffre, Files.readAllBytes(store.chemin(r.id())));
    }

    @Test
    @DisplayName("Purge : clé puis fichier détruits")
    void purge() {
        StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(contenu(50)), 1000);
        assertTrue(stockage.existe(r.id()));
        assertTrue(stockage.detruire(r.id()));
        assertFalse(stockage.existe(r.id()));
        assertFalse(Files.exists(store.chemin(r.id())));
        assertTrue(cles.trouver(r.id()).isEmpty());
        assertFalse(stockage.detruire(r.id()));
    }

    @Test
    @DisplayName("Rotation : DEK réenveloppées sous la nouvelle KEK, fichiers inchangés et lisibles")
    void rotation() throws Exception {
        List<StockageChiffre.ResultatStockage> ecrits = new ArrayList<>();
        List<byte[]> clairs = new ArrayList<>();
        List<byte[]> chiffresAvant = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            byte[] c = contenu(1000 + i);
            clairs.add(c);
            StockageChiffre.ResultatStockage r = stockage.ecrire(new ByteArrayInputStream(c), 10_000);
            ecrits.add(r);
            chiffresAvant.add(Files.readAllBytes(store.chemin(r.id())));
        }
        String ancienne = keyProvider.kekActive();

        RotationKek rotation = new RotationKek(keyProvider, cles);
        RotationKek.Rapport rapport = rotation.executer();

        assertNotEquals(ancienne, rapport.kekActive());
        assertEquals(7, rapport.traitees());
        assertTrue(rapport.echecs().isEmpty());
        assertEquals(0, cles.compterParKek(ancienne));
        assertTrue(keyProvider.kekConnues().contains(ancienne), "l'ancienne KEK reste conservée, désactivée");
        for (int i = 0; i < ecrits.size(); i++) {
            UUID id = ecrits.get(i).id();
            assertEquals(rapport.kekActive(), cles.trouver(id).orElseThrow().kekId());
            assertArrayEquals(chiffresAvant.get(i), Files.readAllBytes(store.chemin(id)), "aucun fichier rechiffré");
            assertArrayEquals(clairs.get(i), lire(id));
        }
        // Idempotente : relancer ne traite plus rien.
        assertEquals(0, rotation.reenvelopper().traitees());
    }

    @Test
    @DisplayName("Retrait d'une ancienne KEK refusé tant que des DEK l'utilisent")
    void retraitKek() {
        stockage.ecrire(new ByteArrayInputStream(contenu(10)), 100);
        String ancienne = keyProvider.kekActive();
        RotationKek rotation = new RotationKek(keyProvider, cles);
        keyProvider.nouvelleKek();
        assertThrows(IllegalStateException.class, () -> rotation.retirer(ancienne));
        rotation.reenvelopper();
        rotation.retirer(ancienne);
        assertFalse(keyProvider.kekConnues().contains(ancienne));
    }

    @Test
    @DisplayName("Rotation sur un fonds plus grand qu'un lot (pagination par identifiant)")
    void rotationParLots() {
        for (int i = 0; i < 1203; i++) {
            stockage.ecrire(new ByteArrayInputStream(new byte[]{(byte) i}), 10);
        }
        RotationKek.Rapport r = new RotationKek(keyProvider, cles).executer();
        assertEquals(1203, r.traitees());
        assertEquals(1203, cles.compterParKek(r.kekActive()));
    }
}
