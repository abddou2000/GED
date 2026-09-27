package com.ipt.ged.ocr.moteur;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tesseract 5, appelé comme binaire depuis Java (§2.3.2, décision D5 : aucun
 * Python, aucun service supplémentaire).
 *
 * <p>L'image est écrite sur l'<b>entrée standard</b> du processus
 * ({@code tesseract stdin stdout}) et le texte lu sur sa sortie standard : la
 * page rendue en clair ne touche jamais un disque, sans dépendre d'un
 * {@code tmpfs} (indisponible sous Windows). Chaque appel est borné par un
 * délai ; au-delà, le processus et ses descendants sont tués.
 *
 * <p>{@code OMP_THREAD_LIMIT=1} : Tesseract ouvre sinon un fil OpenMP par
 * cœur pour chaque page, et plusieurs workers en parallèle se disputeraient
 * les mêmes cœurs — le parallélisme est porté par le nombre de workers.
 */
public class MoteurTesseract implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(MoteurTesseract.class);
    private static final Pattern LANGUE = Pattern.compile("[a-z_]{3,}(\\+[a-z_]{3,})*");
    private static final int STDERR_MAX = 8 * 1024;
    /**
     * Fils des tubes du processus. Pas le pool commun : ces tâches bloquent
     * sur des entrées-sorties et affameraient le reste de l'application.
     */
    private static final ExecutorService TUBES = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tesseract-tube");
        t.setDaemon(true);
        return t;
    });

    private final String commande;
    private final String tessdata;
    private final String oem;
    private final String psm;
    private final List<String> options;

    /**
     * @param tessdata répertoire des modèles ({@code fra}, {@code ara}…) ;
     *                 vide = répertoire par défaut de l'installation.
     */
    public MoteurTesseract(String commande, String tessdata, String oem, String psm) {
        this(commande, tessdata, oem, psm, List.of());
    }

    /** @param options options supplémentaires (ex. {@code -c tessedit_create_tsv=1}). */
    public MoteurTesseract(String commande, String tessdata, String oem, String psm, List<String> options) {
        this.commande = commande;
        this.tessdata = tessdata == null ? "" : tessdata.trim();
        this.oem = oem;
        this.psm = psm;
        this.options = List.copyOf(options);
    }

    @Override
    public String nom() {
        return "Tesseract (binaire, entrée/sortie standard)";
    }

    @Override
    public boolean disponible() {
        try {
            Process p = new ProcessBuilder(commande, "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Modèles présents dans {@code tessdata}. Lu sur le disque plutôt que par
     * {@code --list-langs} : c'est le répertoire que l'application configure,
     * et la réponse ne dépend pas d'un processus.
     */
    @Override
    public Set<String> languesInstallees() {
        if (tessdata.isEmpty()) return Set.of();
        try (Stream<Path> f = Files.list(Path.of(tessdata))) {
            TreeSet<String> l = new TreeSet<>();
            f.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".traineddata"))
                    .forEach(n -> l.add(n.substring(0, n.length() - ".traineddata".length())));
            return Collections.unmodifiableSet(l);
        } catch (IOException e) {
            return Set.of();
        }
    }

    @Override
    public String reconnaitre(byte[] image, String langue, Duration delai) throws EchecOcrException {
        verifierLangue(langue);
        List<String> args = new ArrayList<>(List.of(commande, "stdin", "stdout"));
        if (!tessdata.isEmpty()) {
            args.add("--tessdata-dir");
            args.add(tessdata);
        }
        args.addAll(List.of("-l", langue, "--oem", oem, "--psm", psm));
        args.addAll(options);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.environment().put("OMP_THREAD_LIMIT", "1");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new EchecOcrException(EchecOcrException.Motif.MOTEUR_INDISPONIBLE,
                    "Tesseract introuvable (commande « " + commande + " »)", e);
        }
        // Entrée, sortie et erreur sont servies en parallèle : un tampon de
        // tube plein d'un côté bloquerait sinon le processus de l'autre.
        CompletableFuture<Void> ecriture = CompletableFuture.runAsync(() -> ecrire(p.getOutputStream(), image), TUBES);
        CompletableFuture<byte[]> sortie = CompletableFuture.supplyAsync(() -> lire(p.getInputStream(), Integer.MAX_VALUE), TUBES);
        CompletableFuture<byte[]> erreurs = CompletableFuture.supplyAsync(() -> lire(p.getErrorStream(), STDERR_MAX), TUBES);
        try {
            if (!p.waitFor(delai.toMillis(), TimeUnit.MILLISECONDS)) {
                tuer(p);
                throw new EchecOcrException(EchecOcrException.Motif.DELAI_DEPASSE,
                        "page non reconnue en " + delai.toSeconds() + " s");
            }
            ecriture.get(5, TimeUnit.SECONDS);
            byte[] texte = sortie.get(5, TimeUnit.SECONDS);
            if (p.exitValue() != 0) {
                String err = new String(erreurs.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
                throw new EchecOcrException(EchecOcrException.Motif.ERREUR_MOTEUR,
                        "Tesseract a échoué (code " + p.exitValue() + ") : " + premiereLigne(err));
            }
            return new String(texte, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            tuer(p);
            Thread.currentThread().interrupt();
            throw new EchecOcrException(EchecOcrException.Motif.ERREUR_MOTEUR, "traitement interrompu", e);
        } catch (ExecutionException | TimeoutException e) {
            tuer(p);
            throw new EchecOcrException(EchecOcrException.Motif.ERREUR_MOTEUR, "échange avec Tesseract impossible", e);
        }
    }

    private void verifierLangue(String langue) throws EchecOcrException {
        if (langue == null || !LANGUE.matcher(langue).matches()) {
            throw new EchecOcrException(EchecOcrException.Motif.LANGUE_NON_INSTALLEE, "langue invalide : " + langue);
        }
        Set<String> installees = languesInstallees();
        if (installees.isEmpty()) return; // répertoire par défaut : Tesseract tranchera
        for (String l : langue.split("\\+")) {
            if (!installees.contains(l)) {
                throw new EchecOcrException(EchecOcrException.Motif.LANGUE_NON_INSTALLEE,
                        "modèle « " + l + " » absent de " + tessdata);
            }
        }
    }

    private static void ecrire(OutputStream out, byte[] image) {
        try (out) {
            out.write(image);
        } catch (IOException e) {
            // Tesseract a fermé son entrée (image refusée, processus tué) :
            // son code de sortie dira pourquoi.
            log.debug("Écriture de l'image vers Tesseract interrompue", e);
        }
    }

    private static byte[] lire(InputStream in, int max) {
        try (in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] t = new byte[8192];
            int n;
            while ((n = in.read(t)) >= 0) {
                if (b.size() < max) b.write(t, 0, Math.min(n, max - b.size()));
            }
            return b.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static void tuer(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String premiereLigne(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i).strip() : s;
    }
}
