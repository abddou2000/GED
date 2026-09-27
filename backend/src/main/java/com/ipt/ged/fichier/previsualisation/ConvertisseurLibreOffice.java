package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.fichier.Refus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Conversion bureautique → PDF par LibreOffice en mode sans interface
 * ({@code soffice --headless --convert-to pdf}).
 *
 * <p>La présence de LibreOffice est détectée au premier usage
 * ({@code soffice --version}) et mémorisée : absent, la prévisualisation
 * bureautique est désactivée proprement (503 {@code CONVERSION_INDISPONIBLE},
 * l'écran propose alors le téléchargement), sans empêcher l'application de
 * démarrer. PDF et images restent prévisualisables.
 *
 * <p>Chaque conversion reçoit son propre profil utilisateur LibreOffice :
 * deux instances partageant un profil se bloquent mutuellement, et un profil
 * partagé conserverait l'historique des documents ouverts.
 */
public class ConvertisseurLibreOffice implements ConvertisseurBureautique {

    private static final Logger log = LoggerFactory.getLogger(ConvertisseurLibreOffice.class);
    private static final Duration DELAI_DETECTION = Duration.ofSeconds(30);

    private final List<String> commande;
    private final Duration delai;
    private volatile Boolean disponible;

    /**
     * @param commande exécutable et éventuels arguments de tête (ex.
     *                 {@code ["/usr/bin/soffice"]}) ;
     * @param delai    durée maximale d'une conversion, au-delà de laquelle le
     *                 processus est tué.
     */
    public ConvertisseurLibreOffice(List<String> commande, Duration delai) {
        this.commande = List.copyOf(commande);
        this.delai = delai;
    }

    @Override
    public boolean disponible() {
        Boolean d = disponible;
        if (d == null) {
            d = detecter();
            disponible = d;
        }
        return d;
    }

    private boolean detecter() {
        List<String> cmd = new ArrayList<>(commande);
        cmd.add("--version");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(DELAI_DETECTION.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                log.warn("LibreOffice ne répond pas ({}) : prévisualisation bureautique désactivée", commande);
                return false;
            }
            boolean ok = p.exitValue() == 0;
            if (ok) log.info("LibreOffice détecté ({}) : prévisualisation bureautique active", commande);
            else log.warn("LibreOffice en erreur ({}) : prévisualisation bureautique désactivée", commande);
            return ok;
        } catch (IOException e) {
            log.warn("LibreOffice absent ({}) : prévisualisation bureautique désactivée", commande);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Path convertirEnPdf(Path source, Path dossierSortie) {
        return convertir(source, dossierSortie, "pdf");
    }

    @Override
    public Path convertir(Path source, Path dossierSortie, String cibleConversion) {
        if (!disponible()) {
            throw Refus.conversionIndisponible("LibreOffice n'est pas installé sur le serveur.", null);
        }
        Path profil = null;
        Process p = null;
        try {
            profil = Files.createTempDirectory(dossierSortie, "lo-profil-");
            List<String> cmd = new ArrayList<>(commande);
            cmd.addAll(List.of("--headless", "--norestore", "--nolockcheck", "--nodefault", "--nologo",
                    "-env:UserInstallation=" + profil.toUri(),
                    "--convert-to", cibleConversion, "--outdir", dossierSortie.toString(), source.toString()));
            p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(delai.toMillis(), TimeUnit.MILLISECONDS)) {
                throw Refus.conversionIndisponible("délai de conversion dépassé.", null);
            }
            String nom = source.getFileName().toString();
            int point = nom.lastIndexOf('.');
            Path pdf = dossierSortie.resolve((point > 0 ? nom.substring(0, point) : nom) + ".pdf");
            if (p.exitValue() != 0 || !Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
                throw Refus.conversionIndisponible("la conversion a échoué (code " + p.exitValue() + ").", null);
            }
            return pdf;
        } catch (IOException e) {
            throw Refus.conversionIndisponible("la conversion a échoué.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Refus.conversionIndisponible("conversion interrompue.", e);
        } finally {
            if (p != null && p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            Fichiers.supprimerArborescence(profil);
        }
    }
}
