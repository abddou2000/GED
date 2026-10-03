package com.ipt.ged.charge;

import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.TexteDocument;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Recette E10 — T-028 / P-14 (DAT 4.3.2, 4.3.4, décisions D5 et D6) : rejeu RÉDUIT du banc OCR livré.
 *
 * <p>Réutilise les classes LIVRÉES, sans les modifier : le corpus à vérité connue
 * ({@code CorpusOcr}, test) et la chaîne réelle de l'application
 * ({@link ExtracteurDocumentOcr} : rendu PDFBox à la résolution demandée, PNG sur l'entrée
 * standard, {@link MoteurTesseract} à {@code OMP_THREAD_LIMIT=1}, {@code --oem 1 --psm 3}),
 * avec les modèles du répertoire donné (modèles du dépôt, ou copie compactée en entiers
 * préparée par {@code banc-ocr-reduit.sh}). Java seul (D5), un fil (un cœur), pages traitées
 * l'une après l'autre. Compare aux seuils du §4.3.2 : CER ≤ 5 % (français imprimé propre),
 * ≤ 10 % (arabe), débit ≥ 6 pages/min/cœur ; et, pour information, à la cible du §4.3.4
 * (1 à 3 s par page et par cœur).
 *
 * <p>Temps CPU (tour 7) : celui des processus Tesseract est lu exactement sous Linux
 * ({@code cutime + cstime} de {@code /proc/self/stat} : processus attendus), sinon approché
 * par un relevé toutes les 50 ms ; celui de la chaîne Java (rendu, PNG) par
 * {@link ThreadMXBean}. Le « total » (les deux) est le coût d'une page pour un cœur.
 *
 * <p>ATTENTION : corpus GÉNÉRÉ (Java2D, polices système), pas l'échantillon de 300 pages
 * de MMED exigé par le §4.3.2 : ces CER sont des planchers, pas une validation.
 *
 * <p>Usage : {@code java ... com.ipt.ged.charge.BancOcrReduit <tesseract> <tessdata> [pages] [langue] [dpi]}
 * (dpi : 300 par défaut, valeur des vagues 8 à 11 ; le réglage livré au tour 6 est 200).
 */
public class BancOcrReduit {

    /** Relevé de secours (hors Linux) : CPU cumulé des processus enfants vus toutes les 50 ms. */
    static final java.util.concurrent.ConcurrentHashMap<Long, Long> CPU_ENFANTS = new java.util.concurrent.ConcurrentHashMap<>();
    static boolean exact;

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

    /** cutime + cstime (ms) des enfants attendus, -1 si illisible (hors Linux). */
    static long cpuEnfantsExactMs() {
        try {
            String s = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.US_ASCII);
            String[] c = s.substring(s.lastIndexOf(')') + 2).split(" ");
            // Après « (comm) » : l'état (champ 3) est à l'indice 0 ; cutime = champ 16, cstime = champ 17.
            return (Long.parseLong(c[13]) + Long.parseLong(c[14])) * 1000 / TICKS;
        } catch (Exception e) {
            return -1;
        }
    }

    static final long TICKS = Long.getLong("clk_tck", 100);

    static long cpuEnfantsMs() {
        return exact ? cpuEnfantsExactMs() : CPU_ENFANTS.values().stream().mapToLong(Long::longValue).sum();
    }

    static String charge() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).strip();
        } catch (Exception e) {
            return "?";
        }
    }

    public static void main(String[] args) throws Exception {
        exact = cpuEnfantsExactMs() >= 0;
        if (!exact) surveillerEnfants();
        ThreadMXBean fil = ManagementFactory.getThreadMXBean();
        String tesseract = args[0];
        Path tessdata = Path.of(args[1]).toAbsolutePath();
        int pages = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String langue = args.length > 3 ? args[3] : "ara+fra";
        int dpi = args.length > 4 ? Integer.parseInt(args[4]) : 300;
        Process v = new ProcessBuilder(tesseract, "--version").redirectErrorStream(true).start();
        String version = new String(v.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse("?");
        v.waitFor(10, TimeUnit.SECONDS);
        System.out.println("# " + version + " ; tessdata=" + tessdata + " ; langue=" + langue + " ; " + dpi + " dpi ; "
                + pages + " page(s) par cellule ; processeurs=" + Runtime.getRuntime().availableProcessors()
                + " ; CPU Tesseract " + (exact ? "exact (/proc/self/stat)" : "approché (relevé 50 ms)")
                + " ; charge " + charge());
        for (String m : List.of("ara", "fra")) {
            if (!Files.isRegularFile(tessdata.resolve(m + ".traineddata"))) throw new IllegalStateException("modèle absent : " + m);
        }
        ExtracteurDocumentOcr ex = new ExtracteurDocumentOcr(
                new MoteurTesseract(tesseract, tessdata.toString(), "1", "3"), null, dpi, 25, Duration.ofMinutes(3));
        // Chauffe (JIT, cache disque des modèles), non mesurée.
        for (CorpusOcr.Langue l : List.of(CorpusOcr.Langue.FR, CorpusOcr.Langue.AR)) {
            ex.extraire(new ByteArrayInputStream(CorpusOcr.pages(l, CorpusOcr.Qualite.PROPRE, 1, 99).get(0).pdf()),
                    "application/pdf", langue, ExtracteurDocumentOcr.SuiviPages.AUCUN);
        }

        int echecs = 0;
        long totalMs = 0, cpuTess = 0, cpuJava = 0;
        int totalPages = 0;
        Object[][] cellules = {
                {CorpusOcr.Langue.FR, CorpusOcr.Qualite.PROPRE, 5.0, "CER <= 5 % (français imprimé propre)"},
                {CorpusOcr.Langue.FR, CorpusOcr.Qualite.DEGRADE, Double.NaN, "français dégradé (pas de seuil propre au §4.3.2)"},
                {CorpusOcr.Langue.AR, CorpusOcr.Qualite.PROPRE, 10.0, "CER <= 10 % (arabe)"},
                {CorpusOcr.Langue.AR, CorpusOcr.Qualite.DEGRADE, 10.0, "CER <= 10 % (arabe), qualité dégradée"},
                {CorpusOcr.Langue.MIXTE, CorpusOcr.Qualite.NB, Double.NaN, "bilingue 1 bit (pas de seuil propre au §4.3.2)"},
        };
        for (Object[] c : cellules) {
            CorpusOcr.Langue l = (CorpusOcr.Langue) c[0];
            CorpusOcr.Qualite q = (CorpusOcr.Qualite) c[1];
            double seuil = (double) c[2];
            double cer = 0, wer = 0;
            long ms = 0, cpuCellule = 0;
            for (CorpusOcr.Page p : CorpusOcr.pages(l, q, pages, 1)) {
                long e0 = cpuEnfantsMs(), j0 = fil.getCurrentThreadCpuTime(), t0 = System.nanoTime();
                TexteDocument t = ex.extraire(new ByteArrayInputStream(p.pdf()), "application/pdf", langue,
                        ExtracteurDocumentOcr.SuiviPages.AUCUN);
                ms += (System.nanoTime() - t0) / 1_000_000;
                cpuJava += (fil.getCurrentThreadCpuTime() - j0) / 1_000_000;
                if (!exact) Thread.sleep(120); // le relevé de secours voit la fin du processus
                long ce = cpuEnfantsMs() - e0;
                cpuCellule += ce;
                cpuTess += ce;
                cer += CorpusOcr.cer(p.verite(), t.texte());
                wer += CorpusOcr.wer(p.verite(), t.texte());
            }
            double cerPct = 100 * cer / pages, werPct = 100 * wer / pages, sParPage = ms / 1000.0 / pages;
            totalMs += ms;
            totalPages += pages;
            String detail = String.format(Locale.ROOT,
                    "CER=%.2f %% WER=%.2f %% ; CPU Tesseract %.2f s/page ; écoulé %.2f s/page",
                    cerPct, werPct, cpuCellule / 1000.0 / pages, sParPage);
            String id = "T-028." + l + "." + q;
            if (Double.isNaN(seuil)) {
                System.out.println("RESULTAT|" + id + "|NA|" + c[3] + "|" + detail);
            } else {
                boolean tenu = cerPct <= seuil;
                if (!tenu) echecs++;
                System.out.println("RESULTAT|" + id + "|" + (tenu ? "OK" : "ECHEC") + "|" + c[3] + "|" + detail);
            }
        }
        double sTess = cpuTess / 1000.0 / totalPages, sJava = cpuJava / 1000.0 / totalPages, sTotal = sTess + sJava;
        double ppmTotal = 60 / sTotal;
        // Seuil jugé sur le temps CPU (coût pour un cœur) ; le temps écoulé dépend de la charge du poste.
        boolean debit = ppmTotal >= 6;
        if (!debit) echecs++;
        String mesure = String.format(Locale.ROOT,
                "CPU Tesseract %.2f s/page (%.1f p/min/cœur) + CPU Java %.2f s/page = total %.2f s/page/cœur, soit %.1f p/min/cœur ; écoulé %.2f s/page (%.1f p/min) sur %d pages",
                sTess, 60 / sTess, sJava, sTotal, ppmTotal, totalMs / 1000.0 / totalPages, totalPages * 60_000.0 / totalMs, totalPages);
        System.out.println("RESULTAT|T-028.debit|" + (debit ? "OK" : "ECHEC") + "|débit >= 6 pages/min/cœur (un processus mono-fil, §4.3.2)|" + mesure);
        // §4.3.4 : « 1 à 3 secondes par page et par cœur » — information (P-14), pas un critère de T-028.
        System.out.println("RESULTAT|P-14.debit|" + (sTotal <= 3.0 ? "OK" : "AVERT") + "|cible §4.3.4 : <= 3 s par page et par cœur (information)|"
                + String.format(Locale.ROOT, "total %.2f s/page/cœur ; charge en fin de mesure %s", sTotal, charge()));
        System.out.println("RESULTAT|T-028.corpus|NA|corpus généré à vérité connue, PAS l'échantillon MMED (Q09)|mesure plancher");
        System.exit(echecs == 0 ? 0 : 1);
    }
}
