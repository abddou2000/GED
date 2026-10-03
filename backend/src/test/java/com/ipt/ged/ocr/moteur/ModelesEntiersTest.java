package com.ipt.ged.ocr.moteur;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P-14 / R30 — modèles Tesseract compactés en entiers ({@code combine_tessdata -c})
 * à partir des modèles livrés : conversion, réutilisation tant que la source ne
 * change pas, repli sur les modèles livrés sans l'outil, et reconnaissance réelle
 * avec les modèles compactés (requiert Tesseract, chemin surchargeable par
 * {@code GED_TESSERACT} ; sinon, les cas réels sont ignorés).
 */
class ModelesEntiersTest {

    private static final String TESSERACT = System.getenv().getOrDefault("GED_TESSERACT",
            "C:/Program Files/Tesseract-OCR/tesseract.exe");
    private static final String COMBINE = ModelesEntiers.combineParDefaut(TESSERACT);
    private static final Path LIVRES = Path.of("tessdata").toAbsolutePath();

    private static boolean outilPresent() {
        return Files.isRegularFile(Path.of(TESSERACT)) && Files.isRegularFile(Path.of(COMBINE));
    }

    private static Path source(Path dir, String... langues) throws IOException {
        Path s = Files.createDirectories(dir.resolve("source"));
        for (String l : langues) {
            Files.copy(LIVRES.resolve(l + ".traineddata"), s.resolve(l + ".traineddata"));
        }
        return s;
    }

    @Test
    @DisplayName("Sans combine_tessdata : modèles livrés employés tels quels, aucune conversion")
    void sansOutil(@TempDir Path dir) throws IOException {
        Path s = source(dir, "fra");
        ModelesEntiers.Preparation p = ModelesEntiers.preparer(s, dir.resolve("entiers"), "combine-inexistant-p14");
        assertEquals(s, p.tessdata());
        assertTrue(p.convertis().isEmpty());
    }

    @Test
    @DisplayName("Répertoire source illisible : repli sur la source, sans exception")
    void sourceAbsente(@TempDir Path dir) {
        Path s = dir.resolve("absent");
        assertEquals(s, ModelesEntiers.preparer(s, dir.resolve("entiers"), COMBINE).tessdata());
    }

    @Test
    @DisplayName("fra et ara compactés en entiers (réseau best, int_mode), réutilisés puis refaits si la source change")
    void compacteEtReutilise(@TempDir Path dir) throws Exception {
        assumeTrue(outilPresent(), "Tesseract ou combine_tessdata absent : " + COMBINE);
        Path s = source(dir, "fra", "ara");
        Path cible = dir.resolve("entiers");
        ModelesEntiers.Preparation p = ModelesEntiers.preparer(s, cible, COMBINE);
        assertEquals(cible, p.tessdata());
        assertEquals(List.of("ara", "fra"), p.convertis());
        for (String l : List.of("fra", "ara")) {
            Path m = cible.resolve(l + ".traineddata");
            // best ara : 12,6 Mo en flottants ; compacté : ~2,5 Mo (même réseau, poids sur 8 bits).
            assertTrue(Files.size(m) < Files.size(s.resolve(l + ".traineddata")) / 2, l + " non compacté");
            assertTrue(reseau(m).contains("int_mode=1"), reseau(m));
            assertEquals(architecture(s.resolve(l + ".traineddata")), architecture(m),
                    "même réseau que le modèle livré (tessdata_best), pas celui de tessdata_fast");
        }
        // Deuxième appel : rien n'est refait.
        Path fra = cible.resolve("fra.traineddata");
        FileTime avant = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(fra, avant);
        assertEquals(List.of("ara", "fra"), ModelesEntiers.preparer(s, cible, COMBINE).convertis());
        assertEquals(avant, Files.getLastModifiedTime(fra), "modèle compacté réutilisé");
        // Source remplacée : le modèle est refait (empreinte différente).
        Files.copy(LIVRES.resolve("eng.traineddata"), s.resolve("fra.traineddata"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ModelesEntiers.preparer(s, cible, COMBINE);
        assertNotEquals(avant, Files.getLastModifiedTime(fra), "modèle refait après changement de la source");
        try (var f = Files.list(cible)) {
            assertEquals(4, f.count(), "deux modèles et leurs empreintes, aucun fichier de travail laissé");
        }
    }

    @Test
    @DisplayName("Modèles compactés : scan bilingue reconnu en ara+fra comme avec les modèles livrés")
    void reconnaissance(@TempDir Path dir) throws Exception {
        assumeTrue(outilPresent(), "Tesseract ou combine_tessdata absent : " + COMBINE);
        String police = Scans.policeArabe().orElse(null);
        assumeTrue(police != null, "aucune police arabe installée pour générer le scan");
        Path td = ModelesEntiers.preparer(source(dir, "fra", "ara"), dir.resolve("entiers"), COMBINE).tessdata();
        MoteurTesseract m = new MoteurTesseract(TESSERACT, td.toString(), "1", "3");
        String arabe = "عقد الإيجار السنوي للشركة";
        String texte = m.reconnaitre(Scans.png(Scans.page(police, List.of(arabe,
                "Procès-verbal de réception définitive des travaux"))), "ara+fra", Duration.ofSeconds(60));
        assertTrue(texte.replaceAll("\\s+", "").contains(arabe.replaceAll("\\s+", "")), texte);
        assertTrue(texte.contains("Procès-verbal de réception définitive des travaux"), texte);
    }

    /** « network=[…] » seul : l'architecture des couches, sans le mode de calcul. */
    private static String architecture(Path modele) throws Exception {
        String r = reseau(modele);
        int i = r.indexOf("network=");
        return r.substring(i, r.indexOf(']', i) + 1);
    }

    private static String reseau(Path modele) throws Exception {
        Process p = new ProcessBuilder(COMBINE, "-l", modele.toString()).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        return s.lines().filter(l -> l.contains("network=")).findFirst().orElse(s);
    }
}
