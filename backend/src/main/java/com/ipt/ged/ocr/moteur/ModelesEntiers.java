package com.ipt.ged.ocr.moteur;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Modèles Tesseract <b>compactés en entiers</b> (P-14, risque R30) : copie des
 * modèles livrés ({@code tessdata_best}) dont le réseau LSTM est converti en
 * entiers 8 bits par {@code combine_tessdata -c}, outil livré avec Tesseract.
 *
 * <p>Même réseau que {@code tessdata_best} (et non le réseau réduit de
 * {@code tessdata_fast}) : mesuré sur le corpus à vérité connue
 * ({@code docs/exploitation/ESSAIS-DE-CHARGE.md} § 2.4), le temps CPU de
 * Tesseract par page baisse de 40 % et le CER reste dans les seuils du §4.3.2
 * (arabe propre 2,65 → 2,93 %, français propre 0,86 → 0,87 %). Les modèles
 * livrés restent la seule source (registre des dépendances, SBOM) : la copie
 * en est dérivée de façon déterministe, dans un
 * répertoire de travail, et refaite dès que l'empreinte d'un modèle source
 * change.
 *
 * <p>Si la conversion échoue (outil absent, modèle sans réseau LSTM comme
 * {@code osd}), le modèle est copié tel quel ; si le répertoire de travail est
 * inutilisable, les modèles livrés sont employés directement. L'OCR fonctionne
 * donc toujours, au pire au débit des modèles précis (journal en
 * avertissement).
 */
public final class ModelesEntiers {

    private static final Logger log = LoggerFactory.getLogger(ModelesEntiers.class);
    private static final String EXTENSION = ".traineddata";
    /** Empreinte du modèle source, à côté du modèle converti. */
    private static final String EMPREINTE = ".source-sha256";

    private ModelesEntiers() {
    }

    /** Résultat : répertoire à donner à Tesseract, et modèles réellement convertis. */
    public record Preparation(Path tessdata, List<String> convertis) {
    }

    /**
     * Prépare (ou réutilise) les modèles compactés.
     *
     * @param source  modèles livrés ({@code ged.ocr.tessdata}) ;
     * @param cible   répertoire de travail des modèles compactés ;
     * @param combine binaire {@code combine_tessdata}.
     * @return {@code cible} si au moins un modèle a été converti, sinon {@code source}.
     */
    public static Preparation preparer(Path source, Path cible, String combine) {
        List<Path> modeles;
        try (Stream<Path> f = Files.list(source)) {
            modeles = f.filter(p -> p.getFileName().toString().endsWith(EXTENSION)).sorted().toList();
        } catch (IOException e) {
            log.warn("Modèles OCR introuvables dans {} : modèles livrés employés tels quels", source);
            return new Preparation(source, List.of());
        }
        try {
            Files.createDirectories(cible);
            List<String> convertis = new java.util.ArrayList<>();
            for (Path m : modeles) {
                if (preparerModele(m, cible, combine)) convertis.add(langue(m));
            }
            if (convertis.isEmpty()) {
                log.warn("Aucun modèle OCR compacté en entiers ({} indisponible ?) : modèles précis employés, "
                        + "débit réduit (docs/exploitation/ESSAIS-DE-CHARGE.md § 2.4)", combine);
                return new Preparation(source, List.of());
            }
            log.info("Modèles OCR compactés en entiers dans {} : {}", cible, convertis);
            return new Preparation(cible, List.copyOf(convertis));
        } catch (IOException e) {
            log.warn("Répertoire des modèles compactés {} inutilisable ({}) : modèles précis employés", cible,
                    e.getMessage());
            return new Preparation(source, List.of());
        }
    }

    /** @return vrai si le modèle de {@code cible} est la version compactée de {@code modele}. */
    private static boolean preparerModele(Path modele, Path cible, String combine) throws IOException {
        String nom = modele.getFileName().toString();
        Path sortie = cible.resolve(nom);
        Path empreinte = cible.resolve(nom + EMPREINTE);
        String sha = sha256(modele);
        if (Files.isRegularFile(sortie) && Files.isRegularFile(empreinte)) {
            String[] lu = Files.readString(empreinte, StandardCharsets.US_ASCII).strip().split(" ");
            if (lu.length == 2 && lu[0].equals(sha)) return "entiers".equals(lu[1]);
        }
        // Conversion sur une copie au nom unique, puis remplacement atomique :
        // deux instances sur le même serveur ne se gênent pas.
        Path travail = Files.createTempFile(cible, langue(modele) + ".", EXTENSION);
        try {
            Files.copy(modele, travail, StandardCopyOption.REPLACE_EXISTING);
            boolean entiers = compacter(combine, travail);
            if (!entiers) Files.copy(modele, travail, StandardCopyOption.REPLACE_EXISTING);
            deplacer(travail, sortie);
            Path e = Files.createTempFile(cible, langue(modele) + ".", EMPREINTE);
            Files.writeString(e, sha + " " + (entiers ? "entiers" : "copie") + "\n", StandardCharsets.US_ASCII);
            deplacer(e, empreinte);
            return entiers;
        } finally {
            Files.deleteIfExists(travail);
        }
    }

    private static boolean compacter(String combine, Path fichier) {
        try {
            Process p = new ProcessBuilder(combine, "-c", fichier.toString()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.size(fichier) > 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deplacer(Path de, Path vers) throws IOException {
        try {
            Files.move(de, vers, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(de, vers, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String langue(Path modele) {
        String n = modele.getFileName().toString();
        return n.substring(0, n.length() - EXTENSION.length());
    }

    static String sha256(Path fichier) throws IOException {
        try (InputStream in = Files.newInputStream(fichier)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] t = new byte[64 * 1024];
            int n;
            while ((n = in.read(t)) >= 0) md.update(t, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code combine_tessdata} à côté du binaire Tesseract configuré, sinon dans le chemin. */
    public static String combineParDefaut(String commandeTesseract) {
        Path t = Path.of(commandeTesseract);
        boolean windows = commandeTesseract.toLowerCase(java.util.Locale.ROOT).endsWith(".exe");
        String nom = windows ? "combine_tessdata.exe" : "combine_tessdata";
        return t.getParent() == null ? nom : t.resolveSibling(nom).toString();
    }
}
