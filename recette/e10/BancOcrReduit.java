package com.ipt.ged.charge;

import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.TexteDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Recette E10 — T-028 (DAT 4.3.2, décisions D5 et D6) : rejeu RÉDUIT du banc OCR livré.
 *
 * <p>Réutilise les classes LIVRÉES, sans les modifier : le corpus à vérité connue
 * ({@code CorpusOcr}, test) et la chaîne réelle de l'application
 * ({@link ExtracteurDocumentOcr} : rendu PDFBox 300 dpi, PNG sur l'entrée standard,
 * {@link MoteurTesseract} à {@code OMP_THREAD_LIMIT=1}, {@code --oem 1 --psm 3}), avec les
 * modèles {@code tessdata_best} du dépôt ({@code backend/tessdata}). Java seul (D5), un fil
 * (un cœur), pages traitées l'une après l'autre. Compare aux seuils du §4.3.2 :
 * CER ≤ 5 % (français imprimé propre), ≤ 10 % (arabe), débit ≥ 6 pages/min/cœur.
 *
 * <p>ATTENTION : corpus GÉNÉRÉ (Java2D, polices système), pas l'échantillon de 300 pages
 * de MMED exigé par le §4.3.2 : ces CER sont des planchers, pas une validation.
 *
 * <p>Usage : {@code java ... com.ipt.ged.charge.BancOcrReduit <tesseract.exe> <tessdata> [pages] [langue]}
 */
public class BancOcrReduit {

    /**
     * Temps CPU cumulé des processus Tesseract enfants (relevé toutes les 50 ms) : sur un poste
     * saturé par d'autres charges, le temps écoulé mesure la contention, le temps CPU mesure le
     * coût réel d'une page pour un cœur. Approximation par défaut (derniers 50 ms non vus).
     */
    static final java.util.concurrent.ConcurrentHashMap<Long, Long> CPU_ENFANTS = new java.util.concurrent.ConcurrentHashMap<>();

    static void surveillerEnfants() {
        Thread t = new Thread(() -> {
            while (true) {
                ProcessHandle.current().descendants().forEach(h -> h.info().totalCpuDuration()
                        .ifPresent(d -> CPU_ENFANTS.merge(h.pid(), d.toMillis(), Math::max)));
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static long cpuEnfantsMs() {
        return CPU_ENFANTS.values().stream().mapToLong(Long::longValue).sum();
    }

    public static void main(String[] args) throws Exception {
        surveillerEnfants();
        String tesseract = args[0];
        Path tessdata = Path.of(args[1]).toAbsolutePath();
        int pages = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String langue = args.length > 3 ? args[3] : "ara+fra";
        Process v = new ProcessBuilder(tesseract, "--version").redirectErrorStream(true).start();
        String version = new String(v.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse("?");
        v.waitFor(10, TimeUnit.SECONDS);
        System.out.println("# " + version + " ; tessdata=" + tessdata + " ; langue=" + langue + " ; " + pages
                + " page(s) par cellule ; processeurs=" + Runtime.getRuntime().availableProcessors());
        for (String m : List.of("ara", "fra")) {
            if (!Files.isRegularFile(tessdata.resolve(m + ".traineddata"))) throw new IllegalStateException("modèle absent : " + m);
        }
        ExtracteurDocumentOcr ex = new ExtracteurDocumentOcr(
                new MoteurTesseract(tesseract, tessdata.toString(), "1", "3"), null, 300, 25, Duration.ofMinutes(3));
        // Chauffe (cache disque des modèles), non mesurée.
        ex.extraire(new ByteArrayInputStream(CorpusOcr.pages(CorpusOcr.Langue.FR, CorpusOcr.Qualite.PROPRE, 1, 99).get(0).pdf()),
                "application/pdf", langue, ExtracteurDocumentOcr.SuiviPages.AUCUN);

        int echecs = 0;
        long totalMs = 0;
        long cpuDebut = cpuEnfantsMs();
        int totalPages = 0;
        Object[][] cellules = {
                {CorpusOcr.Langue.FR, CorpusOcr.Qualite.PROPRE, 5.0, "CER <= 5 % (français imprimé propre)"},
                {CorpusOcr.Langue.AR, CorpusOcr.Qualite.PROPRE, 10.0, "CER <= 10 % (arabe)"},
                {CorpusOcr.Langue.AR, CorpusOcr.Qualite.DEGRADE, 10.0, "CER <= 10 % (arabe), qualité dégradée"},
                {CorpusOcr.Langue.MIXTE, CorpusOcr.Qualite.NB, Double.NaN, "bilingue 1 bit (pas de seuil propre au §4.3.2)"},
        };
        for (Object[] c : cellules) {
            CorpusOcr.Langue l = (CorpusOcr.Langue) c[0];
            CorpusOcr.Qualite q = (CorpusOcr.Qualite) c[1];
            double seuil = (double) c[2];
            double cer = 0, wer = 0;
            long ms = 0;
            for (CorpusOcr.Page p : CorpusOcr.pages(l, q, pages, 1)) {
                long t0 = System.nanoTime();
                TexteDocument t = ex.extraire(new ByteArrayInputStream(p.pdf()), "application/pdf", langue,
                        ExtracteurDocumentOcr.SuiviPages.AUCUN);
                ms += (System.nanoTime() - t0) / 1_000_000;
                cer += CorpusOcr.cer(p.verite(), t.texte());
                wer += CorpusOcr.wer(p.verite(), t.texte());
            }
            double cerPct = 100 * cer / pages, werPct = 100 * wer / pages, sParPage = ms / 1000.0 / pages;
            totalMs += ms;
            totalPages += pages;
            String detail = String.format(Locale.ROOT, "CER=%.2f %% WER=%.2f %% ; %.2f s/page (%.1f pages/min/cœur)",
                    cerPct, werPct, sParPage, 60 / sParPage);
            String id = "T-028." + l + "." + q;
            if (Double.isNaN(seuil)) {
                System.out.println("RESULTAT|" + id + "|NA|" + c[3] + "|" + detail);
            } else {
                boolean tenu = cerPct <= seuil;
                if (!tenu) echecs++;
                System.out.println("RESULTAT|" + id + "|" + (tenu ? "OK" : "ECHEC") + "|" + c[3] + "|" + detail);
            }
        }
        Thread.sleep(200);
        double ppm = totalPages * 60_000.0 / totalMs;
        double cpuParPage = (cpuEnfantsMs() - cpuDebut) / 1000.0 / totalPages;
        double ppmCpu = 60 / cpuParPage;
        // Seuil jugé sur le temps CPU de Tesseract (coût pour un cœur) ; le temps écoulé est
        // rapporté tel quel : il dépend de la charge du poste au moment de la mesure.
        boolean debit = ppmCpu >= 6;
        if (!debit) echecs++;
        System.out.println("RESULTAT|T-028.debit|" + (debit ? "OK" : "ECHEC") + "|débit >= 6 pages/min/cœur (un processus mono-fil)|"
                + String.format(Locale.ROOT, "temps CPU Tesseract %.2f s/page soit %.1f pages/min/cœur ; temps écoulé %.2f s/page (%.1f pages/min) sur %d pages",
                cpuParPage, ppmCpu, totalMs / 1000.0 / totalPages, ppm, totalPages));
        System.out.println("RESULTAT|T-028.corpus|NA|corpus généré à vérité connue, PAS l'échantillon MMED (Q09)|mesure plancher");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
