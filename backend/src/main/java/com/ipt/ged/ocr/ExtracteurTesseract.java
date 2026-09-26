package com.ipt.ged.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ipt.ged.ocr.moteur.MoteurTesseract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
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
 * PNG en mémoire, puis transmises au moteur par son entrée standard.
 *
 * <p>Le moteur est interrogé en mode {@code tsv} : il rend alors un mot par
 * ligne avec sa boîte englobante et sa confiance. Le texte à plat est
 * reconstitué à partir de là, ce qui évite un second passage et fournit en prime
 * les positions dont {@link ExtracteurValeurs} a besoin pour lire un tableau.
 */
@Component
public class ExtracteurTesseract implements ExtracteurTexte {

    private static final Logger log = LoggerFactory.getLogger(ExtracteurTesseract.class);

    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp");

    private final String commande;
    private final String langue;
    private final int dpi;
    private final int pagesMax;
    /**
     * Mode {@code tsv} : un mot par ligne avec sa boîte englobante, dont
     * {@link ExtracteurValeurs} a besoin pour lire un tableau. Variable plutôt
     * que le fichier de configuration « tsv » : ce dernier est cherché dans
     * --tessdata-dir, où nos modèles seuls sont déposés — Tesseract rendrait
     * alors du texte brut sans le moindre message.
     */
    private final MoteurTesseract moteur;
    private static final Duration DELAI_PAGE = Duration.ofSeconds(120);
    private final String tessdata;
    private final String psm;
    private final String oem;

    /** Durée de validité du diagnostic de disponibilité, en nanosecondes. */
    private final long cacheNanos;

    /** Dernier verdict connu ; {@code null} tant que le binaire n'a jamais été interrogé. */
    private volatile Boolean disponible;
    private volatile long verifieA;

    public ExtracteurTesseract(@Value("${ged.ocr.commande:tesseract}") String commande,
                               @Value("${ged.ocr.langue:fra+eng}") String langue,
                               @Value("${ged.ocr.dpi:300}") int dpi,
                               @Value("${ged.ocr.pages-max:5}") int pagesMax,
                               @Value("${ged.storage.temp:./storage/temp}") String temp,
                               @Value("${ged.ocr.tessdata:}") String tessdata,
                               @Value("${ged.ocr.psm:3}") String psm,
                               @Value("${ged.ocr.oem:1}") String oem,
                               @Value("${ged.ocr.disponibilite-cache-secondes:300}") long cacheSecondes) {
        this.commande = commande;
        this.langue = langue;
        this.dpi = dpi;
        this.pagesMax = pagesMax;
        this.tessdata = tessdata == null ? "" : tessdata.trim();
        this.psm = psm;
        this.oem = oem;
        this.moteur = new MoteurTesseract(commande, this.tessdata, oem, psm,
                List.of("-c", "tessedit_create_tsv=1"));
        this.cacheNanos = TimeUnit.SECONDS.toNanos(Math.max(0, cacheSecondes));
    }

    @Override public String nom() { return "Tesseract (" + langue + ", psm " + psm + ", oem " + oem + ")"; }
    @Override public int priorite() { return 20; }

    @Override
    public boolean gere(String extension) {
        if (extension == null) return false;
        String e = extension.toLowerCase(Locale.ROOT);
        return "pdf".equals(e) || IMAGES.contains(e);
    }

    /**
     * Le binaire répond-il ? Verdict mémorisé pour une durée courte.
     *
     * <p>Le résultat n'était pas conservé, et la question est posée <b>deux
     * fois</b> par requête : une fois par {@link OcrService} pour choisir
     * l'extracteur, une fois par {@link #extraire} avant de travailler. Chaque
     * aperçu d'indexation lançait donc deux processus {@code tesseract
     * --version} — dix descripteurs de fichier, un quart de seconde — pour une
     * réponse qui ne change pas d'une seconde à l'autre. Et l'écran appelle
     * l'aperçu à chaque sélection de fichier.
     *
     * <p>Le cache est volontairement <b>daté</b> plutôt que définitif : c'était
     * la raison invoquée pour ne rien mémoriser, et elle est valable — installer
     * Tesseract ne doit pas exiger un redémarrage de l'application. Une fenêtre
     * de quelques minutes ({@code ged.ocr.disponibilite-cache-secondes}) tient
     * les deux bouts : plus aucun processus superflu dans une même rafale, et
     * une installation prise en compte peu après.
     *
     * <p>Aucun verrou : deux appels simultanés au tout début peuvent lancer deux
     * sondes, ce qui est sans conséquence — l'opération est en lecture seule et
     * idempotente. Un verrou coûterait plus cher que ce qu'il éviterait.
     */
    @Override
    public boolean disponible() {
        Boolean connu = disponible;
        if (connu != null && System.nanoTime() - verifieA < cacheNanos) {
            return connu;
        }
        boolean verdict = interrogerBinaire();
        disponible = verdict;
        verifieA = System.nanoTime();
        return verdict;
    }

    /**
     * Interroge réellement le binaire. Isolée de {@link #disponible()} pour que
     * le cache soit vérifiable : un test peut compter les interrogations sans
     * dépendre de la présence de Tesseract sur la machine.
     */
    protected boolean interrogerBinaire() {
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
            // Pages rendues en mémoire et transmises à Tesseract par son entrée
            // standard : aucune image en clair n'est plus écrite dans
            // ged.storage.temp (§4.3.4, lot E6).
            List<byte[]> images = "pdf".equalsIgnoreCase(ext) ? rasteriser(fichier) : List.of(Files.readAllBytes(fichier));
            if (images.isEmpty()) return TexteExtrait.aucune("Aucune page à reconnaître.");

            List<MotOcr> mots = new ArrayList<>();
            for (int page = 0; page < images.size(); page++) {
                mots.addAll(LectureTsv.mots(moteur.reconnaitre(images.get(page), langue, DELAI_PAGE), page));
            }

            String resultat = LectureTsv.texte(mots);
            if (resultat.isBlank()) {
                return TexteExtrait.aucune("L'OCR n'a rien reconnu — scan illisible ou page vierge.");
            }
            double confiance = mots.stream().mapToDouble(MotOcr::confiance).average().orElse(0);
            return new TexteExtrait(resultat, TexteExtrait.Provenance.OCR, images.size(),
                    String.format("OCR sur %d page(s), langue %s, confiance moyenne %.0f%%.",
                            images.size(), langue, confiance),
                    mots);
        } catch (Exception e) {
            log.warn("OCR impossible : {}", fichier, e);
            return TexteExtrait.aucune("OCR impossible : " + e.getMessage());
        }
    }

    /**
     * Rend les premières pages du PDF en PNG, en mémoire. Le plafond
     * {@code pages-max} ne vaut que pour cette lecture synchrone (aperçu
     * d'indexation, appelée en direct depuis l'écran) ; la chaîne asynchrone
     * du lot E6 n'en a aucun.
     */
    private List<byte[]> rasteriser(Path pdf) throws Exception {
        List<byte[]> sorties = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), pagesMax);
            for (int i = 0; i < pages; i++) {
                // Rendu direct en niveaux de gris : la binarisation interne de
                // Tesseract part d'une image plus propre, et le PNG produit pèse
                // trois fois moins qu'en couleur.
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.GRAY);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                sorties.add(png.toByteArray());
            }
        }
        return sorties;
    }

    private static String extension(Path fichier) {
        String n = fichier.getFileName().toString();
        int point = n.lastIndexOf('.');
        return point > 0 ? n.substring(point + 1) : "";
    }
}
