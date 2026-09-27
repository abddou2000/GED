package com.ipt.ged.fichier.integrite;

import com.ipt.ged.fichier.DepotClesFichierMemoire;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** §6.1.4 — vérification d'intégrité à la demande et périodique. */
class VerificationIntegriteTest {

    @TempDir Path dossier;

    private FileStoreDisque store;
    private DepotClesFichierMemoire cles;
    private StockageChiffre stockage;
    private List<Object> evenements;
    private VerificationIntegrite verification;

    @BeforeEach
    void preparer() {
        store = new FileStoreDisque(dossier.resolve("coffre"));
        cles = new DepotClesFichierMemoire();
        stockage = new StockageChiffre(store,
                new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mdp".toCharArray(), true), cles, 1024);
        evenements = new ArrayList<>();
        verification = new VerificationIntegrite(stockage, evenements::add);
    }

    private StockageChiffre.ResultatStockage ecrire(int n) {
        return stockage.ecrire(new ByteArrayInputStream(new byte[n]), 1_000_000);
    }

    @Test
    @DisplayName("Fichier intact : conforme, aucun événement")
    void conforme() {
        StockageChiffre.ResultatStockage r = ecrire(5000);
        VerificationIntegrite.Resultat v = verification.verifier(r.id(), r.empreinte(), "version-1");
        assertTrue(v.conforme());
        assertEquals(r.empreinte(), v.empreinteCalculee());
        assertTrue(evenements.isEmpty());
        // Casse de l'hexadécimal indifférente.
        assertTrue(verification.verifier(r.id(), r.empreinte().toUpperCase(), "v").conforme());
    }

    @Test
    @DisplayName("Fichier altéré sur disque : ALTERE, alerte et événement d'audit")
    void altere() throws Exception {
        StockageChiffre.ResultatStockage r = ecrire(5000);
        try (RandomAccessFile f = new RandomAccessFile(store.chemin(r.id()).toFile(), "rw")) {
            f.seek(f.length() - 3);
            int b = f.read();
            f.seek(f.length() - 3);
            f.write(b ^ 0x55);
        }
        VerificationIntegrite.Resultat v = verification.verifier(r.id(), r.empreinte(), "version-2");
        assertEquals(VerificationIntegrite.Statut.ALTERE, v.statut());
        assertEquals(1, evenements.size());
        VerificationIntegrite.AnomalieIntegrite a = (VerificationIntegrite.AnomalieIntegrite) evenements.get(0);
        assertEquals(r.id(), a.fichierId());
        assertEquals("version-2", a.reference());
    }

    @Test
    @DisplayName("Fichier authentique mais différent de celui déposé : EMPREINTE_DIVERGENTE")
    void divergent() {
        StockageChiffre.ResultatStockage r = ecrire(10);
        String autre = ecrire(11).empreinte();
        assertEquals(VerificationIntegrite.Statut.EMPREINTE_DIVERGENTE,
                verification.verifier(r.id(), autre, "v").statut());
        assertEquals(1, evenements.size());
    }

    @Test
    @DisplayName("Fichier ou clé absents : ABSENT")
    void absent() throws Exception {
        StockageChiffre.ResultatStockage r = ecrire(10);
        store.supprimer(r.id());
        assertEquals(VerificationIntegrite.Statut.ABSENT, verification.verifier(r.id(), r.empreinte(), "v").statut());
        StockageChiffre.ResultatStockage s = ecrire(10);
        cles.supprimer(s.id());
        assertEquals(VerificationIntegrite.Statut.ABSENT, verification.verifier(s.id(), s.empreinte(), "v").statut());
    }

    @Test
    @DisplayName("Passe périodique sur tout le fonds : décompte par statut")
    void passePeriodique() throws Exception {
        StockageChiffre.ResultatStockage a = ecrire(100), b = ecrire(200), c = ecrire(300);
        try (RandomAccessFile f = new RandomAccessFile(store.chemin(c.id()).toFile(), "rw")) {
            f.seek(40);
            int octet = f.read();
            f.seek(40);
            f.write(octet ^ 0xFF);
        }
        SourceEmpreintes source = visiteur -> List.of(
                new SourceEmpreintes.EmpreinteAttendue(a.id(), a.empreinte(), "a"),
                new SourceEmpreintes.EmpreinteAttendue(b.id(), b.empreinte(), "b"),
                new SourceEmpreintes.EmpreinteAttendue(c.id(), c.empreinte(), "c")).forEach(visiteur);
        Map<VerificationIntegrite.Statut, Integer> bilan = new VerificationPeriodique(verification, source).executer();
        assertEquals(2, bilan.get(VerificationIntegrite.Statut.CONFORME));
        assertEquals(1, bilan.get(VerificationIntegrite.Statut.ALTERE));
    }
}
