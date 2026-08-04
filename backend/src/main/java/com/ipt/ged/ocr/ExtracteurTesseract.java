package com.ipt.ged.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Étage 2 de l'OCRisation : reconnaissance optique par Tesseract.
 *
 * <p>Passe par le binaire système plutôt que par une liaison Java (JNA), pour
 * trois raisons : aucune bibliothèque native à embarquer, mise à jour du moteur
 * indépendante de l'application, et désactivation propre quand il est absent —
 * {@link #disponible()} renvoie alors {@code false} et {@link OcrService}
 * l'ignore sans erreur.
 *
 * <p>Un PDF scanné ne contient qu'une image : ses pages sont d'abord rendues en
 * PNG dans {@code ged.storage.temp}, puis soumises au moteur.
 *
 * <p><b>Non vérifié sur ce poste</b> : Tesseract n'y est pas installé. Le chemin
 * de code est écrit et compilé, mais son exécution reste à valider sur un
 * serveur équipé (voir {@code OcrController#diagnostic}).
 */
@Component
public class ExtracteurTesseract implements ExtracteurTexte {

    private static final Logger log = LoggerFactory.getLogger(ExtracteurTesseract.class);

    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp");

    private final String commande;
    private final String langue;
    private final int dpi;
    private final int pagesMax;
    private final Path tempDir;

    public ExtracteurTesseract(@Value("${ged.ocr.commande:tesseract}") String commande,
                               @Value("${ged.ocr.langue:fra+eng}") String langue,
                               @Value("${ged.ocr.dpi:300}") int dpi,
                               @Value("${ged.ocr.pages-max:5}") int pagesMax,
                               @Value("${ged.storage.temp:./storage/temp}") String temp) {
        this.commande = commande;
        this.langue = langue;
        this.dpi = dpi;
        this.pagesMax = pagesMax;
        this.tempDir = Path.of(temp).toAbsolutePath().normalize();
    }

    @Override public String nom() { return "Tesseract (" + commande + ", " + langue + ")"; }
    @Override public int priorite() { return 20; }

    @Override
    public boolean gere(String extension) {
        if (extension == null) return false;
        String e = extension.toLowerCase(Locale.ROOT);
        return "pdf".equals(e) || IMAGES.contains(e);
    }

    /** Le binaire répond-il ? Résultat non mis en cache : une installation peut survenir sans redémarrage. */
    @Override
    public boolean disponible() {
        try {
            Process p = new ProcessBuilder(commande, "--version").redirectErrorStream(true).start();
            boolean fini = p.waitFor(5, TimeUnit.SECONDS);
            if (!fini) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;   // binaire absent : l'étage est simplement ignoré
        }
    }

    @Override
    public TexteExtrait extraire(Path fichier) {
        if (!disponible()) {
            return TexteExtrait.aucune("Tesseract n'est pas installé sur ce serveur (commande « "
                    + commande + " » introuvable).");
        }
        String ext = extension(fichier);
        try {
            Files.createDirectories(tempDir);
            List<Path> images = "pdf".equalsIgnoreCase(ext) ? rasteriser(fichier) : List.of(fichier);
            if (images.isEmpty()) return TexteExtrait.aucune("Aucune page à reconnaître.");

            StringBuilder texte = new StringBuilder();
            try {
                for (Path image : images) {
                    texte.append(lancerTesseract(image)).append('\n');
                }
            } finally {
                if ("pdf".equalsIgnoreCase(ext)) images.forEach(this::supprimerTemporaire);
            }

            String resultat = texte.toString().strip();
            return resultat.isBlank()
                    ? TexteExtrait.aucune("L'OCR n'a rien reconnu — scan illisible ou page vierge.")
                    : new TexteExtrait(resultat, TexteExtrait.Provenance.OCR, images.size(),
                            "OCR sur " + images.size() + " page(s), langue " + langue + ".");
        } catch (Exception e) {
            log.warn("OCR impossible : {}", fichier, e);
            return TexteExtrait.aucune("OCR impossible : " + e.getMessage());
        }
    }

    /** Rend les premières pages du PDF en PNG dans le dossier temporaire. */
    private List<Path> rasteriser(Path pdf) throws Exception {
        List<Path> sorties = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), pagesMax);
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                Path sortie = tempDir.resolve("ocr-" + System.nanoTime() + "-p" + i + ".png");
                ImageIO.write(image, "png", sortie.toFile());
                sorties.add(sortie);
            }
        }
        return sorties;
    }

    /** « stdout » demande à Tesseract d'écrire sur la sortie standard plutôt que dans un fichier. */
    private String lancerTesseract(Path image) throws Exception {
        Process p = new ProcessBuilder(commande, image.toString(), "stdout", "-l", langue)
                .redirectErrorStream(false).start();
        String sortie = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Tesseract n'a pas répondu dans le délai imparti.");
        }
        return sortie;
    }

    private void supprimerTemporaire(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception e) { log.debug("Temporaire non supprimé : {}", p); }
    }

    private static String extension(Path fichier) {
        String n = fichier.getFileName().toString();
        int point = n.lastIndexOf('.');
        return point > 0 ? n.substring(point + 1) : "";
    }
}
