package com.ipt.ged.supervision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sonde {@code referentielFichiers} : le référentiel des documents est présent,
 * lisible, inscriptible, et n'est pas plein (DAT 6.7).
 *
 * <p>Aucune écriture d'essai : le référentiel est en écriture unique (DAT
 * 6.1.1), une sonde qui y déposerait un fichier toutes les 15 secondes y
 * laisserait des traces et fausserait le rapprochement des orphelins après
 * restauration (DAT 6.5). Les droits sont donc vérifiés sans écrire.
 *
 * <p>DOWN au-delà du seuil critique d'occupation ; l'alerte d'exploitation
 * part bien avant, à 80 %, depuis la métrique {@code ged_stockage_*}.
 */
@Component("referentielFichiersHealthIndicator")
public class SondeReferentielFichiers implements HealthIndicator {

    private final Path racine;
    private final double seuilCritique;

    public SondeReferentielFichiers(@Value("${ged.storage.root:./storage/ged}") String racine,
                                    @Value("${ged.supervision.stockage.seuil-occupation-critique:0.95}") double seuilCritique) {
        this.racine = Path.of(racine).toAbsolutePath().normalize();
        this.seuilCritique = seuilCritique;
    }

    Path racine() {
        return racine;
    }

    @Override
    public Health health() {
        if (!Files.isDirectory(racine)) {
            return Health.down().withDetail("chemin", racine.toString())
                    .withDetail("anomalie", "répertoire absent").build();
        }
        if (!Files.isReadable(racine) || !Files.isWritable(racine)) {
            return Health.down().withDetail("chemin", racine.toString())
                    .withDetail("anomalie", "droits de lecture ou d'écriture manquants").build();
        }
        try {
            FileStore volume = Files.getFileStore(racine);
            long total = volume.getTotalSpace();
            long libre = volume.getUsableSpace();
            double occupation = total > 0 ? 1.0 - (double) libre / total : 1.0;
            var sante = occupation >= seuilCritique ? Health.down()
                    .withDetail("anomalie", "volume plein au-delà de " + Math.round(seuilCritique * 100) + " %")
                    : Health.up();
            return sante.withDetail("chemin", racine.toString())
                    .withDetail("octetsLibres", libre)
                    .withDetail("octetsTotal", total)
                    .withDetail("occupation", Math.round(occupation * 1000) / 1000.0)
                    .build();
        } catch (IOException e) {
            return Health.down(e).withDetail("chemin", racine.toString()).build();
        }
    }
}
