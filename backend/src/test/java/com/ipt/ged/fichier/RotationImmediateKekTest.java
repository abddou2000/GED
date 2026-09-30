package com.ipt.ged.fichier;

import com.ipt.ged.fichier.cles.CleFichier;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.cles.LanceurRotationKek;
import com.ipt.ged.fichier.cles.RotationKek;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Modèle de menaces (P-11 §3 bis) : compromission de la KEK. L'exploitant doit
 * pouvoir rendre la clé volée inutile pour les DEK courantes sans attendre la
 * rotation annuelle, et savoir si l'opération est incomplète.
 */
class RotationImmediateKekTest {

    @TempDir Path dossier;

    private KeystoreKeyProvider keyProvider;
    private DepotClesFichierMemoire cles;
    private StockageChiffre stockage;

    @BeforeEach
    void preparer() {
        keyProvider = new KeystoreKeyProvider(dossier.resolve("cles/kek.p12"), "mdp".toCharArray(), true);
        cles = new DepotClesFichierMemoire();
        stockage = new StockageChiffre(new FileStoreDisque(dossier.resolve("coffre")), keyProvider, cles, 4096);
        for (int i = 0; i < 3; i++) {
            stockage.ecrire(new ByteArrayInputStream(new byte[]{(byte) i}), 10);
        }
    }

    private LanceurRotationKek lanceur(boolean retirerAncienne) {
        return new LanceurRotationKek(keyProvider, new RotationKek(keyProvider, cles), retirerAncienne);
    }

    @Test
    @DisplayName("Rotation immédiate : nouvelle KEK active, aucune DEK sous la clé compromise, conservée pour les sauvegardes")
    void rotationSansRetrait() {
        String compromise = keyProvider.kekActive();

        lanceur(false).run(new DefaultApplicationArguments());

        assertNotEquals(compromise, keyProvider.kekActive());
        assertEquals(0, cles.compterParKek(compromise));
        assertTrue(keyProvider.kekConnues().contains(compromise), "sans demande explicite, l'ancienne KEK reste pour les restaurations");
    }

    @Test
    @DisplayName("Sur demande, la KEK compromise est retirée du keystore après un réenveloppement complet")
    void rotationAvecRetrait() {
        String compromise = keyProvider.kekActive();

        lanceur(true).run(new DefaultApplicationArguments());

        assertFalse(keyProvider.kekConnues().contains(compromise));
        // Rechargé depuis le disque : le retrait est bien persisté.
        KeystoreKeyProvider relu = new KeystoreKeyProvider(dossier.resolve("cles/kek.p12"), "mdp".toCharArray(), false);
        assertFalse(relu.kekConnues().contains(compromise));
    }

    @Test
    @DisplayName("Une DEK non réenveloppable : échec du lancement, la KEK compromise n'est pas retirée")
    void rotationIncomplete() {
        String compromise = keyProvider.kekActive();
        // Enveloppe altérée : ne se désenveloppe plus, comme une ligne corrompue en base.
        cles.enregistrer(new CleFichier(UUID.randomUUID(), new byte[60], compromise, CleFichier.AES_256_GCM));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> lanceur(true).run(new DefaultApplicationArguments()));

        assertTrue(e.getMessage().contains("Rotation incomplète"), e.getMessage());
        assertTrue(keyProvider.kekConnues().contains(compromise));
        assertEquals(1, cles.compterParKek(compromise));
    }
}
