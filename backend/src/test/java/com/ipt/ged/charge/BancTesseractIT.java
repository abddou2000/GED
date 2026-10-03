package com.ipt.ged.charge;

import com.ipt.ged.charge.CorpusOcr.Langue;
import com.ipt.ged.charge.CorpusOcr.Page;
import com.ipt.ged.charge.CorpusOcr.Qualite;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.ModelesEntiers;
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

    /* ---------- P-14 / R30 (tour 6) : réglages du débit, en temps CPU par page ---------- */

    /** Cellules du banc réduit de qa (T-028) et le français dégradé : seuils du §4.3.2 sur les trois premières. */
    private static final List<String> CELLULES_DEBIT = List.of("FR.PROPRE", "AR.PROPRE", "AR.DEGRADE", "FR.DEGRADE",
            "MIXTE.NB");

    /**
     * Un réglage de la chaîne : répertoire de modèles, langues, segmentation,
     * résolution du rendu, options du moteur, binarisation de la page avant
     * Tesseract.
     */
    private record Reglage(String nom, Path tessdata, String langue, int psm, int dpi, List<String> options,
                           boolean binariser) {
    }

    /** Répertoire de modèles composé : fra et ara pris chacun dans un répertoire source. */
    private static Path composer(String nom, Path fra, Path ara) throws IOException {
        Path d = Path.of("target", "charge", "tessdata_" + nom).toAbsolutePath();
        Files.createDirectories(d);
        Files.copy(fra.resolve("fra.traineddata"), d.resolve("fra.traineddata"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ara.resolve("ara.traineddata"), d.resolve("ara.traineddata"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return d;
    }

    /**
     * Modèles {@code best} compactés en entiers ({@code combine_tessdata -c},
     * livré avec Tesseract) : même réseau que {@code best}, calcul en entiers
     * 8 bits comme {@code tessdata_fast}.
     */
    private static Path bestEntiers() throws Exception {
        // Par la classe de l'application (ged.ocr.modeles=entiers), sur une copie de fra et ara.
        Path d = Path.of("target", "charge", "tessdata_best_int").toAbsolutePath();
        ModelesEntiers.Preparation p = ModelesEntiers.preparer(composer("best_src", BEST, BEST), d,
                ModelesEntiers.combineParDefaut(TESSERACT));
        assumeTrue(p.convertis().containsAll(List.of("fra", "ara")), "combine_tessdata -c indisponible");
        return p.tessdata();
    }

    /**
     * Seuil d'Otsu puis image 1 bit : Tesseract reçoit une page déjà binarisée.
     * Mesure si la binarisation faite en Java (au lieu de celle de Tesseract)
     * fait gagner du temps.
     */
    static BufferedImage binariser(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gris = src;
        if (src.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            gris = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            var g = gris.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        var r = gris.getRaster();
        int[] hist = new int[256];
        int[] ligne = new int[w];
        for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, ligne);
            for (int v : ligne) hist[v]++;
        }
        long total = (long) w * h, somme = 0;
        for (int i = 0; i < 256; i++) somme += (long) i * hist[i];
        long sommeB = 0, poidsB = 0;
        double meilleur = -1;
        int seuil = 128;
        for (int t = 0; t < 256; t++) {
            poidsB += hist[t];
            if (poidsB == 0) continue;
            long poidsF = total - poidsB;
            if (poidsF == 0) break;
            sommeB += (long) t * hist[t];
            double mB = (double) sommeB / poidsB, mF = (double) (somme - sommeB) / poidsF;
            double entre = (double) poidsB * poidsF * (mB - mF) * (mB - mF);
            if (entre > meilleur) {
                meilleur = entre;
                seuil = t;
            }
        }
        BufferedImage nb = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        var o = nb.getRaster();
        for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, ligne);
            for (int x = 0; x < w; x++) ligne[x] = ligne[x] > seuil ? 1 : 0;
            o.setSamples(0, y, w, 1, 0, ligne);
        }
        return nb;
    }

    /** Moteur qui binarise la page (décodée du PNG) avant de la passer à Tesseract. */
    private static com.ipt.ged.ocr.moteur.OcrEngine binarisant(com.ipt.ged.ocr.moteur.OcrEngine m) {
        return new com.ipt.ged.ocr.moteur.OcrEngine() {
            @Override public String nom() { return m.nom() + " + binarisation"; }
            @Override public boolean disponible() { return m.disponible(); }
            @Override public java.util.Set<String> languesInstallees() { return m.languesInstallees(); }
            @Override public String reconnaitre(byte[] image, String langue, Duration delai)
                    throws com.ipt.ged.ocr.moteur.EchecOcrException {
                try {
                    return m.reconnaitre(png(binariser(ImageIO.read(new ByteArrayInputStream(image)))), langue, delai);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static Map<String, Reglage> reglages() throws Exception {
        Path bestInt = bestEntiers();
        Path fraFastAraBest = composer("fra_fast_ara_best", FAST, BEST);
        Path fraFastAraInt = composer("fra_fast_ara_int", FAST, bestInt);
        Path fraIntAraFast = composer("fra_int_ara_fast", bestInt, FAST);
        List<String> sansInv = List.of("-c", "tessedit_do_invert=0");
        Map<String, Reglage> r = new LinkedHashMap<>();
        for (Reglage x : List.of(
                new Reglage("best", BEST, "ara+fra", 3, 300, List.of(), false),
                new Reglage("fast", FAST, "ara+fra", 3, 300, List.of(), false),
                new Reglage("best_int", bestInt, "ara+fra", 3, 300, List.of(), false),
                new Reglage("fra_fast_ara_best", fraFastAraBest, "ara+fra", 3, 300, List.of(), false),
                new Reglage("fra_fast_ara_int", fraFastAraInt, "ara+fra", 3, 300, List.of(), false),
                new Reglage("fra_int_ara_fast", fraIntAraFast, "ara+fra", 3, 300, List.of(), false),
                new Reglage("best_250", BEST, "ara+fra", 3, 250, List.of(), false),
                new Reglage("best_200", BEST, "ara+fra", 3, 200, List.of(), false),
                new Reglage("best_psm4", BEST, "ara+fra", 4, 300, List.of(), false),
                new Reglage("best_sans_inversion", BEST, "ara+fra", 3, 300, sansInv, false),
                new Reglage("best_binarise", BEST, "ara+fra", 3, 300, List.of(), true),
                new Reglage("best_int_250", bestInt, "ara+fra", 3, 250, List.of(), false),
                new Reglage("best_int_200", bestInt, "ara+fra", 3, 200, List.of(), false),
                new Reglage("best_int_sans_inversion", bestInt, "ara+fra", 3, 300, sansInv, false),
                new Reglage("best_int_250_sans_inversion", bestInt, "ara+fra", 3, 250, sansInv, false),
                new Reglage("best_int_200_sans_inversion", bestInt, "ara+fra", 3, 200, sansInv, false),
                new Reglage("fast_sans_inversion", FAST, "ara+fra", 3, 300, sansInv, false),
                new Reglage("fast_250_sans_inversion", FAST, "ara+fra", 3, 250, sansInv, false),
                new Reglage("fast_200", FAST, "ara+fra", 3, 200, List.of(), false),
                new Reglage("fra_fast_ara_int_200", fraFastAraInt, "ara+fra", 3, 200, List.of(), false),
                new Reglage("best_int_150", bestInt, "ara+fra", 3, 150, List.of(), false))) {
            r.put(x.nom(), x);
        }
        return r;
    }

    /**
     * P-14 / R30 : temps CPU de Tesseract par page (processus enfants attendus,
     * {@link Mesures#cpuEnfantsMs()}), temps CPU Java de la chaîne (rendu, PNG,
     * binarisation éventuelle) et CER, réglage par réglage, pages traitées l'une
     * après l'autre sur un fil (un cœur), chaque réglage sur les mêmes pages.
     * Réglages choisis par {@code GED_BANC_REGLAGES} (noms séparés par des
     * virgules ; tous par défaut). Le débit par cœur se juge sur le temps CPU :
     * sur un poste partagé, le temps écoulé mesure aussi la contention.
     */
    @Test
    void reglagesDebit() throws Exception {
        assumeTrue(Mesures.cpuEnfantsMs() >= 0, "temps CPU des enfants illisible (Linux seulement)");
        Map<String, Reglage> tous = reglages();
        String choix = System.getenv().getOrDefault("GED_BANC_REGLAGES", "");
        List<String> noms = choix.isBlank() ? List.copyOf(tous.keySet()) : List.of(choix.split(","));
        var fil = java.lang.management.ManagementFactory.getThreadMXBean();
        extracteur(BEST, 3, 300).extraire(new ByteArrayInputStream(CORPUS.get("FR.PROPRE").get(0).pdf()), PDF,
                "ara+fra", ExtracteurDocumentOcr.SuiviPages.AUCUN); // chauffe : JIT, cache disque des modèles
        Mesures.noterPoste("banc.debit");
        StringBuilder synthese = new StringBuilder();
        for (String nom : noms) {
            Reglage rg = tous.get(nom.strip());
            if (rg == null) throw new IllegalArgumentException("réglage inconnu : " + nom);
            var moteur = new MoteurTesseract(TESSERACT, rg.tessdata().toString(), "1", String.valueOf(rg.psm()),
                    rg.options());
            ExtracteurDocumentOcr ex = new ExtracteurDocumentOcr(rg.binariser() ? binarisant(moteur) : moteur, null,
                    rg.dpi(), 25, Duration.ofMinutes(3));
            ex.extraire(new ByteArrayInputStream(CORPUS.get("AR.PROPRE").get(0).pdf()), PDF, rg.langue(),
                    ExtracteurDocumentOcr.SuiviPages.AUCUN); // chauffe des modèles de ce réglage
            long cpuTess = 0, cpuJava = 0, ecoule = 0;
            int pages = 0;
            StringBuilder cers = new StringBuilder();
            for (String cle : CELLULES_DEBIT) {
                double cer = 0;
                long cpuCellule = 0;
                List<Page> lot = CORPUS.get(cle);
                for (Page p : lot) {
                    long e0 = Mesures.cpuEnfantsMs(), j0 = fil.getCurrentThreadCpuTime(), t0 = System.nanoTime();
                    TexteDocument t = ex.extraire(new ByteArrayInputStream(p.pdf()), PDF, rg.langue(),
                            ExtracteurDocumentOcr.SuiviPages.AUCUN);
                    ecoule += (System.nanoTime() - t0) / 1_000_000;
                    cpuJava += (fil.getCurrentThreadCpuTime() - j0) / 1_000_000;
                    long c = Mesures.cpuEnfantsMs() - e0;
                    cpuTess += c;
                    cpuCellule += c;
                    cer += CorpusOcr.cer(p.verite(), t.texte());
                }
                pages += lot.size();
                String k = "banc.debit." + nom + "." + cle;
                Mesures.noter(k + ".cer_pct", Mesures.f(100 * cer / lot.size()));
                Mesures.noter(k + ".s_cpu_tesseract_par_page", Mesures.f(cpuCellule / 1000.0 / lot.size()));
                cers.append(' ').append(cle).append('=').append(Mesures.f(100 * cer / lot.size()));
            }
            double sTess = cpuTess / 1000.0 / pages, sJava = cpuJava / 1000.0 / pages;
            String ligne = nom + " | " + rg.tessdata().getFileName() + " " + rg.langue() + " psm" + rg.psm() + " "
                    + rg.dpi() + "dpi" + (rg.options().isEmpty() ? "" : " " + String.join(" ", rg.options()))
                    + (rg.binariser() ? " binarisé" : "") + " | CPU Tesseract " + Mesures.f(sTess) + " s/page ("
                    + Mesures.f(60 / sTess) + " p/min/cœur) | CPU Java " + Mesures.f(sJava) + " s/page | total "
                    + Mesures.f(sTess + sJava) + " s/page (" + Mesures.f(60 / (sTess + sJava)) + " p/min/cœur) | écoulé "
                    + Mesures.f(ecoule / 1000.0 / pages) + " s/page | CER %" + cers + " | " + pages + " pages";
            Mesures.noter("banc.debit." + nom, ligne);
            synthese.append(System.lineSeparator()).append(ligne);
        }
        Mesures.noterPoste("banc.debit.fin");
        Mesures.noter("banc.debit.synthese", synthese);
    }

    /**
     * OpenMP : même page, même modèle, un processus Tesseract limité à un fil
     * ({@code OMP_THREAD_LIMIT=1}, réglage de {@link MoteurTesseract}) ou libre
     * d'ouvrir un fil par cœur. Temps CPU et temps écoulé par page.
     */
    @Test
    void openMpTempsCpu() throws Exception {
        assumeTrue(Mesures.cpuEnfantsMs() >= 0, "temps CPU des enfants illisible (Linux seulement)");
        List<byte[]> images = new ArrayList<>();
        for (String cle : CELLULES_DEBIT) {
            for (Page p : CORPUS.get(cle)) {
                try (PDDocument doc = Loader.loadPDF(p.pdf())) {
                    images.add(png(new PDFRenderer(doc).renderImageWithDPI(0, 300, ImageType.GRAY)));
                }
            }
        }
        ocrDirect(images.get(0), BEST, "ara+fra", 3, "1");
        Mesures.noterPoste("banc.openmp");
        for (String omp : new String[] {"1", null}) {
            long e0 = Mesures.cpuEnfantsMs(), t0 = System.nanoTime();
            for (byte[] img : images) ocrDirect(img, BEST, "ara+fra", 3, omp);
            double cpu = (Mesures.cpuEnfantsMs() - e0) / 1000.0 / images.size();
            double ec = (System.nanoTime() - t0) / 1e9 / images.size();
            Mesures.noter("banc.openmp." + (omp == null ? "libre" : "omp1"), "CPU Tesseract " + Mesures.f(cpu)
                    + " s/page, écoulé " + Mesures.f(ec) + " s/page, " + images.size() + " pages");
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
