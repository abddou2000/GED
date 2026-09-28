package com.ipt.ged.charge;

import com.ipt.ged.charge.CorpusOcr.Langue;
import com.ipt.ged.charge.CorpusOcr.Page;
import com.ipt.ged.charge.CorpusOcr.Qualite;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.TexteDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Banc du moteur OCR (§4.3.1, §4.3.2, §6.6), hors contexte Spring mais par la
 * <b>chaîne réelle</b> de l'application ({@link ExtracteurDocumentOcr} :
 * rendu PDFBox, PNG sur l'entrée standard, {@link MoteurTesseract} à
 * {@code OMP_THREAD_LIMIT=1}). Mesure le débit et la qualité (CER, WER sur un
 * corpus à vérité connue, {@link CorpusOcr}) selon les modèles
 * ({@code tessdata_best} / {@code tessdata_fast}), les langues, la
 * résolution de rendu, le mode de segmentation et le parallélisme.
 *
 * <p>Exécution à la demande (jamais par {@code mvn test}, suffixe {@code IT}) :
 * <pre>
 * mvn test -Dtest=BancTesseractIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Modèles rapides attendus dans {@code GED_TESSDATA_FAST}
 * ({@code target/charge/tessdata_fast} par défaut) ; mesures dans
 * {@code target/charge/mesures.txt}.
 */
class BancTesseractIT {

    private static final String TESSERACT = System.getenv().getOrDefault("GED_TESSERACT",
            "C:/Program Files/Tesseract-OCR/tesseract.exe");
    private static final Path BEST = Path.of(System.getenv().getOrDefault("GED_TESSDATA_BEST", "tessdata"))
            .toAbsolutePath();
    private static final Path FAST = Path.of(System.getenv().getOrDefault("GED_TESSDATA_FAST",
            "target/charge/tessdata_fast")).toAbsolutePath();
    /** fra de tessdata_fast et ara de tessdata_best, préparé par {@link #generer()}. */
    private static final Path MIXTE = Path.of("target", "charge", "tessdata_mixte").toAbsolutePath();
    private static final int PAGES = Integer.parseInt(System.getenv().getOrDefault("GED_BANC_PAGES", "5"));
    private static final String PDF = "application/pdf";

    private static final Map<String, List<Page>> CORPUS = new LinkedHashMap<>();

    @BeforeAll
    static void generer() throws IOException {
        assumeTrue(Files.isRegularFile(Path.of(TESSERACT)), "Tesseract absent");
        assumeTrue(Files.isRegularFile(FAST.resolve("fra.traineddata"))
                && Files.isRegularFile(FAST.resolve("ara.traineddata")), "tessdata_fast absent");
        Files.createDirectories(MIXTE);
        Files.copy(FAST.resolve("fra.traineddata"), MIXTE.resolve("fra.traineddata"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BEST.resolve("ara.traineddata"), MIXTE.resolve("ara.traineddata"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (Langue l : Langue.values()) {
            for (Qualite q : Qualite.values()) {
                CORPUS.put(l + "." + q, CorpusOcr.pages(l, q, PAGES, 1));
            }
        }
        Mesures.noter("banc.corpus", PAGES + " pages par langue et qualité ; " + version());
    }

    private static String version() {
        try {
            Process p = new ProcessBuilder(TESSERACT, "--version").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return s.lines().findFirst().orElse("?");
        } catch (Exception e) {
            return "?";
        }
    }

    private static ExtracteurDocumentOcr extracteur(Path tessdata, int psm, int dpi) {
        return extracteur(tessdata, psm, dpi, List.of());
    }

    private static ExtracteurDocumentOcr extracteur(Path tessdata, int psm, int dpi, List<String> options) {
        return new ExtracteurDocumentOcr(new MoteurTesseract(TESSERACT, tessdata.toString(), "1", String.valueOf(psm),
                options), null, dpi, 25, Duration.ofMinutes(3));
    }

    /**
     * best, fast, ou « mixte » : fra rapide et ara précis dans un même
     * répertoire (Tesseract charge chaque langue depuis le répertoire donné).
     */
    private static Path modele(String nom) {
        return switch (nom) {
            case "best" -> BEST;
            case "fast" -> FAST;
            default -> MIXTE;
        };
    }

    /** Une série : pages traitées l'une après l'autre sur un fil (un cœur). */
    private static void serie(String cle, List<Page> pages, String modele, String langue, int psm, int dpi)
            throws Exception {
        serie(cle, pages, modele, langue, psm, dpi, List.of());
    }

    private static void serie(String cle, List<Page> pages, String modele, String langue, int psm, int dpi,
                              List<String> options) throws Exception {
        ExtracteurDocumentOcr ex = extracteur(modele(modele), psm, dpi, options);
        long ms = 0;
        double cer = 0, wer = 0, pire = 0;
        int perf;
        try (Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
            for (Page p : pages) {
                long t0 = System.nanoTime();
                TexteDocument t = ex.extraire(new ByteArrayInputStream(p.pdf()), PDF, langue,
                        ExtracteurDocumentOcr.SuiviPages.AUCUN);
                ms += (System.nanoTime() - t0) / 1_000_000;
                double c = CorpusOcr.cer(p.verite(), t.texte());
                cer += c;
                wer += CorpusOcr.wer(p.verite(), t.texte());
                pire = Math.max(pire, c);
            }
            perf = pm.moyenne();
        }
        String k = "banc." + modele + "." + cle;
        Mesures.noter(k + ".s_par_page", Mesures.f(ms / 1000.0 / pages.size()));
        Mesures.noter(k + ".cer_pct", Mesures.f(100 * cer / pages.size()));
        Mesures.noter(k + ".wer_pct", Mesures.f(100 * wer / pages.size()));
        Mesures.noter(k + ".cer_pire_page_pct", Mesures.f(100 * pire));
        Mesures.noter(k + ".performance_cpu_pct", perf);
    }

    /** Chaque série est jouée avec best puis fast aussitôt : même charge de fond pour les deux. */
    private static void deuxModeles(String cle, List<Page> pages, String langue, int psm, int dpi) throws Exception {
        serie(cle, pages, "best", langue, psm, dpi);
        serie(cle, pages, "fast", langue, psm, dpi);
    }

    /**
     * Matrice principale : langue par défaut de la chaîne (fra+ara), 300 dpi,
     * psm 3, sur les trois langues de document et les trois qualités ; puis
     * modèle monolingue (fra seul, ara seul), psm 6 et rendu à 250 dpi.
     */
    @Test
    void qualiteEtDebit() throws Exception {
        extracteur(BEST, 3, 300).extraire(new ByteArrayInputStream(CORPUS.get("FR.PROPRE").get(0).pdf()), PDF,
                "fra+ara", ExtracteurDocumentOcr.SuiviPages.AUCUN); // chauffe : JIT, cache disque des modèles
        Mesures.noterPoste("banc.qualite");
        for (Langue l : Langue.values()) {
            for (Qualite q : Qualite.values()) {
                deuxModeles(l + "." + q + ".fra+ara.psm3.300", CORPUS.get(l + "." + q), "fra+ara", 3, 300);
            }
        }
        for (Qualite q : List.of(Qualite.PROPRE, Qualite.DEGRADE)) {
            deuxModeles("FR." + q + ".fra.psm3.300", CORPUS.get("FR." + q), "fra", 3, 300);
            deuxModeles("AR." + q + ".ara.psm3.300", CORPUS.get("AR." + q), "ara", 3, 300);
        }
        for (Langue l : Langue.values()) {
            deuxModeles(l + ".DEGRADE.fra+ara.psm6.300", CORPUS.get(l + ".DEGRADE"), "fra+ara", 6, 300);
            for (Qualite q : Qualite.values()) {
                deuxModeles(l + "." + q + ".fra+ara.psm3.250", CORPUS.get(l + "." + q), "fra+ara", 3, 250);
            }
        }
    }

    /**
     * Options du moteur, à modèle égal : {@code tessedit_do_invert=0} (Tesseract
     * 5 relit en négatif chaque ligne peu sûre : coûteux, inutile sur des scans
     * de courrier) et {@code --dpi 300} (l'image PNG ne porte pas sa résolution,
     * Tesseract l'estime). Puis le modèle mixte : fra rapide, ara précis.
     */
    @Test
    void optionsMoteur() throws Exception {
        extracteur(BEST, 3, 300).extraire(new ByteArrayInputStream(CORPUS.get("FR.PROPRE").get(0).pdf()), PDF,
                "fra+ara", ExtracteurDocumentOcr.SuiviPages.AUCUN);
        Mesures.noterPoste("banc.options");
        List<String> sansInversion = List.of("-c", "tessedit_do_invert=0");
        List<String> sansInversionDpi = List.of("-c", "tessedit_do_invert=0", "--dpi", "300");
        for (String cle : List.of("FR.PROPRE", "AR.PROPRE", "MIXTE.NB", "FR.DEGRADE")) {
            serie(cle + ".fra+ara.psm3.300.defaut", CORPUS.get(cle), "best", "fra+ara", 3, 300);
            serie(cle + ".fra+ara.psm3.300.sans_inversion", CORPUS.get(cle), "best", "fra+ara", 3, 300, sansInversion);
            serie(cle + ".fra+ara.psm3.300.sans_inversion_dpi", CORPUS.get(cle), "best", "fra+ara", 3, 300,
                    sansInversionDpi);
        }
        // Modèle mixte (fra rapide, ara précis) : ne vaut que si fra rapide fait gagner du temps.
        for (String cle : List.of("FR.PROPRE", "MIXTE.NB")) {
            serie(cle + ".fra+ara.psm3.300.defaut", CORPUS.get(cle), "mixte", "fra+ara", 3, 300);
        }
    }

    /**
     * Ordre des langues : la première est la langue principale de Tesseract,
     * les suivantes ne sont essayées que sur les mots peu sûrs. Avec
     * {@code fra+ara}, une page arabe perd beaucoup (CER triplé) : on mesure
     * {@code ara+fra} sur les trois langues de document.
     */
    @Test
    void ordreDesLangues() throws Exception {
        extracteur(BEST, 3, 300).extraire(new ByteArrayInputStream(CORPUS.get("FR.PROPRE").get(0).pdf()), PDF,
                "ara+fra", ExtracteurDocumentOcr.SuiviPages.AUCUN);
        Mesures.noterPoste("banc.langues");
        for (String cle : List.of("FR.PROPRE", "FR.DEGRADE", "AR.PROPRE", "AR.DEGRADE", "MIXTE.PROPRE", "MIXTE.NB")) {
            for (String langue : List.of("fra+ara", "ara+fra")) {
                serie(cle + "." + langue + ".psm3.300", CORPUS.get(cle), "best", langue, 3, 300);
            }
        }
    }

    /**
     * Attachement (D6 : 400 à 800 pages) traité par UN worker : la chaîne va
     * page par page, sa durée est linéaire en pages et sa mémoire bornée à une
     * page rendue. On mesure les {@code GED_BANC_PAGES_ATTACHEMENT} premières
     * pages (50 par défaut) du même document bilingue 1 bit que
     * {@code EssaiOcrChargeIT} et on extrapole à 400 et 800 pages.
     */
    @Test
    void attachement() throws Exception {
        int pages = Integer.parseInt(System.getenv().getOrDefault("GED_BANC_PAGES_ATTACHEMENT", "50"));
        byte[] pdf = CorpusOcr.document(Langue.MIXTE, Qualite.NB, pages, 400);
        Mesures.noterPoste("banc.attachement");
        for (String langue : List.of("fra+ara", "ara+fra")) {
            try (Mesures.PicTas pic = new Mesures.PicTas(); Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
                long t0 = System.nanoTime();
                TexteDocument t = extracteur(BEST, 3, 300).extraire(new ByteArrayInputStream(pdf), PDF, langue,
                        ExtracteurDocumentOcr.SuiviPages.AUCUN);
                double sParPage = (System.nanoTime() - t0) / 1e9 / t.nbPages();
                String k = "banc.attachement." + langue;
                Mesures.noter(k + ".pages", t.nbPages());
                Mesures.noter(k + ".s_par_page", Mesures.f(sParPage) + " ; " + pm.resume());
                Mesures.noter(k + ".extrapole_400_pages_min", Mesures.f(sParPage * 400 / 60));
                Mesures.noter(k + ".extrapole_800_pages_min", Mesures.f(sParPage * 800 / 60));
                Mesures.noter(k + ".pic_tas_mo", pic.picMo());
            }
        }
    }

    /**
     * Où passe le temps d'une page dans la chaîne : chargement et rendu PDFBox,
     * encodage PNG, reconnaissance. Compare aussi une image PGM (non
     * compressée) au PNG sur l'entrée standard de Tesseract.
     */
    @Test
    void decomposition() throws Exception {
        for (String cle : List.of("FR.PROPRE", "FR.NB", "FR.DEGRADE", "AR.PROPRE")) {
            Page p = CORPUS.get(cle).get(0);
            long rendu = 0, png = 0, pgm = 0, tessPng = 0, tessPgm = 0;
            int tours = 3;
            byte[] imgPng = null, imgPgm = null;
            for (int i = 0; i < tours; i++) {
                long t0 = System.nanoTime();
                BufferedImage img;
                try (PDDocument doc = Loader.loadPDF(p.pdf())) {
                    img = new PDFRenderer(doc).renderImageWithDPI(0, 300, ImageType.GRAY);
                }
                long t1 = System.nanoTime();
                imgPng = png(img);
                long t2 = System.nanoTime();
                imgPgm = pgm(img);
                long t3 = System.nanoTime();
                String a = ocrDirect(imgPng, BEST, "fra+ara", 3, "1").texte();
                long t4 = System.nanoTime();
                String b = ocrDirect(imgPgm, BEST, "fra+ara", 3, "1").texte();
                long t5 = System.nanoTime();
                assertEquals(a, b, "même texte quel que soit le format de l'image");
                rendu += t1 - t0;
                png += t2 - t1;
                pgm += t3 - t2;
                tessPng += t4 - t3;
                tessPgm += t5 - t4;
            }
            String k = "banc.decomposition." + cle;
            Mesures.noter(k + ".rendu_300dpi_ms", rendu / tours / 1_000_000);
            Mesures.noter(k + ".encodage_png_ms", png / tours / 1_000_000);
            Mesures.noter(k + ".encodage_pgm_ms", pgm / tours / 1_000_000);
            Mesures.noter(k + ".png_ko", imgPng.length / 1024);
            Mesures.noter(k + ".pgm_ko", imgPgm.length / 1024);
            Mesures.noter(k + ".tesseract_best_png_ms", tessPng / tours / 1_000_000);
            Mesures.noter(k + ".tesseract_best_pgm_ms", tessPgm / tours / 1_000_000);
        }
    }

    /**
     * Parallélisme : un processus multi-fil (OpenMP sans limite) traitant les
     * pages l'une après l'autre, contre N processus mono-fil
     * ({@code OMP_THREAD_LIMIT=1}) ou bi-fil en parallèle ; puis la chaîne
     * complète de l'application (rendu + PNG + Tesseract) à N workers. Débit
     * agrégé en pages par minute.
     */
    @Test
    void parallelisme() throws Exception {
        List<Page> lot = new ArrayList<>();
        for (Langue l : Langue.values()) {
            for (Qualite q : Qualite.values()) {
                lot.addAll(CORPUS.get(l + "." + q).subList(0, Math.min(3, PAGES)));
            }
        }
        List<byte[]> images = new ArrayList<>();
        for (Page p : lot) {
            try (PDDocument doc = Loader.loadPDF(p.pdf())) {
                images.add(png(new PDFRenderer(doc).renderImageWithDPI(0, 300, ImageType.GRAY)));
            }
        }
        Mesures.noter("banc.parallele.pages", images.size());
        for (String m : List.of("best", "fast")) {
            Path td = modele(m);
            String k = "banc.parallele." + m;
            Mesures.noterPoste(k);
            try (Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
                long t0 = System.nanoTime();
                for (byte[] img : images) ocrDirect(img, td, "fra+ara", 3, null);
                Mesures.noter(k + ".1_processus_openmp.pages_par_minute", Mesures.f(ppm(images.size(), t0))
                        + " ; " + pm.resume());
            }
            for (int n : List.of(1, 2, 3, 4, 6, 8, 12)) {
                try (Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
                    Mesures.noter(k + "." + n + "_processus_omp1.pages_par_minute",
                            Mesures.f(enParallele(n, images, td, "1")) + " ; " + pm.resume());
                }
            }
            for (int n : List.of(3, 6)) {
                try (Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
                    Mesures.noter(k + "." + n + "_processus_omp2.pages_par_minute",
                            Mesures.f(enParallele(n, images, td, "2")) + " ; " + pm.resume());
                }
            }
            // Chaîne complète de l'application, N workers.
            for (int n : List.of(4, 6, 12)) {
                ExecutorService pool = Executors.newFixedThreadPool(n);
                ExtracteurDocumentOcr ex = extracteur(td, 3, 300);
                try (Mesures.PicTas pic = new Mesures.PicTas(); Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
                    long d0 = System.nanoTime();
                    List<Future<TexteDocument>> f = new ArrayList<>();
                    for (Page p : lot) {
                        f.add(pool.submit(() -> ex.extraire(new ByteArrayInputStream(p.pdf()), PDF, "fra+ara",
                                ExtracteurDocumentOcr.SuiviPages.AUCUN)));
                    }
                    for (Future<TexteDocument> x : f) x.get();
                    Mesures.noter(k + ".chaine_" + n + "_workers.pages_par_minute", Mesures.f(ppm(lot.size(), d0))
                            + " ; " + pm.resume());
                    Mesures.noter(k + ".chaine_" + n + "_workers.pic_tas_mo", pic.picMo());
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    private static double enParallele(int n, List<byte[]> images, Path td, String omp) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            long d0 = System.nanoTime();
            List<Future<Resultat>> f = new ArrayList<>();
            for (byte[] img : images) f.add(pool.submit(() -> ocrDirect(img, td, "fra+ara", 3, omp)));
            for (Future<Resultat> x : f) x.get();
            return ppm(images.size(), d0);
        } finally {
            pool.shutdownNow();
        }
    }

    private static double ppm(int pages, long debutNanos) {
        return pages * 60_000.0 / ((System.nanoTime() - debutNanos) / 1_000_000.0);
    }

    private record Resultat(long ms, String texte) {
    }

    /** Appel direct du binaire (image sur l'entrée standard), pour régler OpenMP à volonté. */
    private static Resultat ocrDirect(byte[] image, Path tessdata, String langue, int psm, String omp) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(TESSERACT, "stdin", "stdout", "--tessdata-dir", tessdata.toString(),
                "-l", langue, "--oem", "1", "--psm", String.valueOf(psm));
        if (omp != null) pb.environment().put("OMP_THREAD_LIMIT", omp);
        else pb.environment().remove("OMP_THREAD_LIMIT");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        long t0 = System.nanoTime();
        Process p = pb.start();
        CompletableFuture<Void> e = CompletableFuture.runAsync(() -> {
            try (var out = p.getOutputStream()) {
                out.write(image);
            } catch (IOException ignore) {
                // le code de sortie dira pourquoi
            }
        });
        String sortie = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("délai dépassé");
        }
        e.join();
        if (p.exitValue() != 0) throw new IllegalStateException("Tesseract : code " + p.exitValue());
        return new Resultat((System.nanoTime() - t0) / 1_000_000, sortie);
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** PGM binaire (P5) : en-tête texte puis un octet par pixel, sans compression. */
    static byte[] pgm(BufferedImage gris) {
        int w = gris.getWidth(), h = gris.getHeight();
        byte[] entete = ("P5\n" + w + " " + h + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[entete.length + w * h];
        System.arraycopy(entete, 0, out, 0, entete.length);
        var raster = gris.getRaster();
        int[] ligne = new int[w];
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, ligne);
            int o = entete.length + y * w;
            for (int x = 0; x < w; x++) out[o + x] = (byte) ligne[x];
        }
        return out;
    }
}
