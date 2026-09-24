package com.ipt.ged.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Point 9 de l'audit — <b>chaque aperçu lançait deux processus Tesseract</b>.
 *
 * <p>{@code disponible()} n'était pas mémorisé et la question est posée deux
 * fois par requête : une fois par {@code OcrService} pour choisir l'extracteur,
 * une fois par {@code extraire} avant de travailler. Mesuré : +10 descripteurs
 * et 256 ms par appel, sur une route que l'écran sollicite à chaque sélection de
 * fichier.
 *
 * <p>Le test compte les interrogations réelles du binaire — il ne dépend donc
 * pas de la présence de Tesseract sur la machine d'intégration.
 */
class DisponibiliteTesseractTest {

    /** Extracteur dont l'interrogation du binaire est comptée et simulée. */
    private static final class Sonde extends ExtracteurTesseract {
        private final AtomicInteger appels = new AtomicInteger();
        private final boolean verdict;

        Sonde(boolean verdict, long cacheSecondes) {
            super("tesseract-inexistant", "fra", 300, 1, "./target/test-temp", "", "3", "1", cacheSecondes);
            this.verdict = verdict;
        }

        @Override
        protected boolean interrogerBinaire() {
            appels.incrementAndGet();
            return verdict;
        }
    }

    @Test
    @DisplayName("9a. Le verdict de disponibilité est mémorisé : une seule interrogation du binaire pour dix appels")
    void verdictMemorise() {
        Sonde sonde = new Sonde(true, 300);

        for (int i = 0; i < 10; i++) {
            assertTrue(sonde.disponible());
        }

        // Avant correction : 10 processus « tesseract --version » lancés.
        assertEquals(1, sonde.appels.get(),
                "le binaire a été interrogé plusieurs fois pour une réponse qui ne change pas");
    }

    @Test
    @DisplayName("9b. Le cache reste daté : avec une fenêtre nulle, une installation est reprise sans redémarrage")
    void cacheDate() {
        Sonde sonde = new Sonde(false, 0);

        sonde.disponible();
        sonde.disponible();
        sonde.disponible();

        /* La raison invoquée pour ne rien mémoriser — « une installation peut
           survenir sans redémarrage » — reste satisfaite : le cache expire. On
           le vérifie ici avec une fenêtre de zéro seconde, qui force une
           nouvelle interrogation à chaque appel. */
        assertEquals(3, sonde.appels.get(), "le cache ne se périme jamais");
    }
}
