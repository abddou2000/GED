package com.ipt.ged.charge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Journal des mesures des essais de charge : une ligne « clé = valeur » par
 * mesure, sur la sortie standard et dans {@code target/charge/mesures.txt}.
 * Échantillonneur du tas de la JVM (pic observé toutes les 200 ms).
 */
final class Mesures {

    private static final Path FICHIER = Path.of("target", "charge", "mesures.txt");
    private static final MemoryMXBean MEMOIRE = ManagementFactory.getMemoryMXBean();

    private Mesures() {
    }

    static synchronized void noter(String cle, Object valeur) {
        String ligne = Instant.now() + " " + cle + " = " + valeur;
        System.out.println("MESURE " + ligne);
        try {
            Files.createDirectories(FICHIER.getParent());
            Files.writeString(FICHIER, ligne + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static long tasUtiliseMo() {
        return MEMOIRE.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    /**
     * Charge CPU de tout le poste (%) sur 3 s, avant une mesure : le poste est
     * partagé (suites de tests des autres membres, instance de démonstration),
     * un débit n'a de sens qu'accompagné de la charge de fond.
     */
    static double chargeCpuSysteme() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double somme = 0;
        int n = 0;
        os.getCpuLoad(); // la première lecture n'a pas de référence
        for (int i = 0; i < 6; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            double c = os.getCpuLoad();
            if (c >= 0) {
                somme += c;
                n++;
            }
        }
        return n == 0 ? -1 : 100 * somme / n;
    }

    /**
     * Niveau de performance effectif du processeur (% de la fréquence
     * nominale, compteur Windows « % Processor Performance »), -1 hors
     * Windows. Le poste d'essai est un portable : alimentation insuffisante ou
     * échauffement le brident (observé : 30 % pendant des heures), et un débit
     * mesuré sans ce niveau ne se compare à rien.
     */
    static int performanceCpu() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return -1;
        try {
            // Aucun guillemet dans la commande : Java les transmet mal à un
            // argument Windows qui contient des espaces.
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_PerfFormattedData_Counters_ProcessorInformation | Where-Object Name -eq _Total"
                            + " | ForEach-Object PercentProcessorPerformance").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Performance CPU moyenne pendant une mesure (relevé toutes les 5 s) : le
     * bridage du portable va et vient (30 % puis 109 % à deux minutes
     * d'intervalle), un relevé unique avant la mesure ne suffit pas.
     */
    static final class PerfMoyenne implements AutoCloseable {
        private final List<Integer> releves = Collections.synchronizedList(new ArrayList<>());
        private final Thread fil;
        private volatile boolean actif = true;

        PerfMoyenne() {
            fil = new Thread(() -> {
                while (actif) {
                    int p = performanceCpu();
                    if (p > 0) releves.add(p);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "perf-cpu");
            fil.setDaemon(true);
            fil.start();
        }

        /** Moyenne des relevés (-1 si aucun). */
        int moyenne() {
            synchronized (releves) {
                return releves.isEmpty() ? -1
                        : (int) Math.round(releves.stream().mapToInt(Integer::intValue).average().orElse(0));
            }
        }

        /** Moyenne, minimum et maximum relevés, pour le journal. */
        String resume() {
            synchronized (releves) {
                if (releves.isEmpty()) return "performance CPU inconnue";
                return "perf CPU moy " + moyenne() + " % (min " + Collections.min(releves) + ", max "
                        + Collections.max(releves) + ", " + releves.size() + " relevés)";
            }
        }

        @Override
        public void close() {
            actif = false;
            fil.interrupt();
        }
    }

    /**
     * Temps CPU (utilisateur + système, en ms) des processus enfants terminés et
     * attendus par la JVM : champs {@code cutime} et {@code cstime} de
     * {@code /proc/self/stat} (Linux, 100 tops par seconde), -1 ailleurs. Exact
     * pour les processus Tesseract d'une page, contrairement à un relevé
     * périodique qui manque leurs derniers instants. Sur un poste partagé, c'est
     * le coût réel d'une page pour un cœur ; le temps écoulé mesure la contention.
     */
    static long cpuEnfantsMs() {
        try {
            String s = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.US_ASCII);
            String[] champs = s.substring(s.lastIndexOf(')') + 2).split(" ");
            // Après « (comm) » : champ 3 (état) à l'indice 0 ; cutime = champ 16, cstime = champ 17.
            return (Long.parseLong(champs[13]) + Long.parseLong(champs[14])) * 10;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Charge de fond et performance du processeur, à noter avant chaque phase mesurée. */
    static void noterPoste(String cle) {
        noter(cle + ".poste", "charge CPU de fond " + f(chargeCpuSysteme()) + " %, performance CPU "
                + performanceCpu() + " %");
    }

    /** Percentile (0-100) d'une liste de durées en millisecondes. */
    static double centile(List<Long> ms, double p) {
        List<Long> l = new ArrayList<>(ms);
        Collections.sort(l);
        if (l.isEmpty()) return 0;
        int i = (int) Math.ceil(p / 100.0 * l.size()) - 1;
        return l.get(Math.max(0, Math.min(i, l.size() - 1)));
    }

    /** Pic du tas pendant une phase. */
    static final class PicTas implements AutoCloseable {
        private final AtomicLong pic = new AtomicLong();
        private final Thread fil;
        private volatile boolean actif = true;

        PicTas() {
            fil = new Thread(() -> {
                while (actif) {
                    pic.accumulateAndGet(tasUtiliseMo(), Math::max);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "pic-tas");
            fil.setDaemon(true);
            fil.start();
        }

        long picMo() {
            return pic.get();
        }

        @Override
        public void close() {
            actif = false;
            fil.interrupt();
        }
    }
}
