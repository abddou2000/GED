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
 *
 * <p>La marque écrite à côté de chaque copie ({@code <sha256 source> <mode>})
 * porte le mode réellement obtenu : {@code entiers} (converti),
 * {@code non-convertible} (l'outil a tourné et refusé le modèle, ex.
 * {@code osd} : pas réessayé tant que la source ne change pas) ou
 * {@code repli} (outil indisponible : copie telle quelle, <b>refaite</b> au
 * premier démarrage où l'outil est là — ANO-E6-003). La marque {@code copie}
 * des versions précédentes, qui ne distinguait pas ces deux cas, est traitée
 * comme {@code repli}.
 */
public final class ModelesEntiers {

    private static final Logger log = LoggerFactory.getLogger(ModelesEntiers.class);
    private static final String EXTENSION = ".traineddata";
    /** Empreinte du modèle source, à côté du modèle converti. */
    private static final String EMPREINTE = ".source-sha256";

    private ModelesEntiers() {
    }

    /** Mode obtenu pour un modèle, écrit dans sa marque. */
    enum Obtenu {
        ENTIERS("entiers"), NON_CONVERTIBLE("non-convertible"), REPLI("repli");

        final String marque;

        Obtenu(String marque) {
            this.marque = marque;
        }
    }

    /** Résultat : répertoire à donner à Tesseract, et modèles réellement convertis. */
    public record Preparation(Path tessdata, List<String> convertis) {

        /** {@code entiers} si au moins un modèle est compacté, sinon {@code repli} (modèles précis). */
        public String mode() {
            return convertis.isEmpty() ? "repli" : "entiers";
        }
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
            boolean outil = outilDisponible(combine);
            List<String> convertis = new java.util.ArrayList<>();
            for (Path m : modeles) {
                if (preparerModele(m, cible, combine, outil) == Obtenu.ENTIERS) convertis.add(langue(m));
            }
            if (convertis.isEmpty()) {
                if (outil) {
                    log.warn("Aucun modèle OCR convertible en entiers par {} : modèles précis employés, "
                            + "débit réduit (docs/exploitation/ESSAIS-DE-CHARGE.md § 2.4)", combine);
                } else {
                    log.warn("Aucun modèle OCR compacté en entiers ({} indisponible) : modèles précis employés, "
                            + "débit réduit (docs/exploitation/ESSAIS-DE-CHARGE.md § 2.4) ; conversion refaite "
                            + "au premier démarrage où l'outil sera présent", combine);
                }
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

    /** @return le mode obtenu pour la copie de {@code modele} dans {@code cible}. */
    private static Obtenu preparerModele(Path modele, Path cible, String combine, boolean outil) throws IOException {
        String nom = modele.getFileName().toString();
        Path sortie = cible.resolve(nom);
        Path empreinte = cible.resolve(nom + EMPREINTE);
        String sha = sha256(modele);
        if (Files.isRegularFile(sortie) && Files.isRegularFile(empreinte)) {
            String[] lu = Files.readString(empreinte, StandardCharsets.US_ASCII).strip().split(" ");
            if (lu.length == 2 && lu[0].equals(sha)) {
                if (Obtenu.ENTIERS.marque.equals(lu[1])) return Obtenu.ENTIERS;
                if (Obtenu.NON_CONVERTIBLE.marque.equals(lu[1])) return Obtenu.NON_CONVERTIBLE;
                // « repli » (ou « copie », ancienne marque ambiguë) : refait dès que l'outil est là.
                if (!outil) return Obtenu.REPLI;
            }
        }
        // Conversion sur une copie au nom unique, puis remplacement atomique :
        // deux instances sur le même serveur ne se gênent pas.
        Path travail = Files.createTempFile(cible, langue(modele) + ".", EXTENSION);
        try {
            Files.copy(modele, travail, StandardCopyOption.REPLACE_EXISTING);
            Obtenu obtenu = outil ? compacter(combine, travail) : Obtenu.REPLI;
            if (obtenu != Obtenu.ENTIERS) Files.copy(modele, travail, StandardCopyOption.REPLACE_EXISTING);
            deplacer(travail, sortie);
            Path e = Files.createTempFile(cible, langue(modele) + ".", EMPREINTE);
            Files.writeString(e, sha + " " + obtenu.marque + "\n", StandardCharsets.US_ASCII);
            deplacer(e, empreinte);
            return obtenu;
        } finally {
            Files.deleteIfExists(travail);
        }
    }

    /**
     * L'outil peut-il être exécuté ? Lancé sans argument, {@code combine_tessdata}
     * affiche son mode d'emploi et rend 1 ; introuvable, il ne démarre pas (ou
     * rend 126/127 quand le système ne peut pas l'exécuter).
     */
    static boolean outilDisponible(String combine) {
        try {
            Process p = new ProcessBuilder(combine).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return !inexecutable(p.exitValue());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Codes de sortie d'un programme que le système n'a pas pu exécuter (POSIX). */
    private static boolean inexecutable(int code) {
        return code == 126 || code == 127;
    }

    /** Outil exécuté mais modèle refusé : {@code NON_CONVERTIBLE} ; outil injoignable : {@code REPLI}. */
    private static Obtenu compacter(String combine, Path fichier) {
        try {
            Process p = new ProcessBuilder(combine, "-c", fichier.toString()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return Obtenu.REPLI;
            }
            if (inexecutable(p.exitValue())) return Obtenu.REPLI;
            return p.exitValue() == 0 && Files.size(fichier) > 0 ? Obtenu.ENTIERS : Obtenu.NON_CONVERTIBLE;
        } catch (IOException e) {
            return Obtenu.REPLI;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Obtenu.REPLI;
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
