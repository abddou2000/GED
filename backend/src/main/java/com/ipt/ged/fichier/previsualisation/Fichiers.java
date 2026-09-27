package com.ipt.ged.fichier.previsualisation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Nettoyage des répertoires de travail de la conversion. */
public final class Fichiers {

    private static final Logger log = LoggerFactory.getLogger(Fichiers.class);

    private Fichiers() {}

    /**
     * Supprime une arborescence, sans lever : appelé dans des {@code finally}
     * où une erreur masquerait la cause d'origine. Un échec est signalé au
     * journal, car il laisse une copie en clair dans le répertoire de travail.
     */
    public static void supprimerArborescence(Path racine) {
        if (racine == null || !Files.exists(racine)) return;
        try (Stream<Path> tout = Files.walk(racine)) {
            tout.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.error("Copie de travail non supprimée : {}", p, e);
                }
            });
        } catch (IOException e) {
            log.error("Répertoire de travail non nettoyé : {}", racine, e);
        }
    }
}
