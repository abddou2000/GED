package com.ipt.ged.supervision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sonde {@code filesTraitement} : profondeur de chaque file de traitement
 * déclarée ({@link FileDeTraitement}), DOWN si l'une déborde ou ne répond pas.
 *
 * <p>Sans file déclarée (avant E6), la sonde est UP et le dit : l'exploitant
 * voit qu'aucune file n'est encore supervisée, au lieu d'un silence ambigu.
 */
@Component("filesTraitementHealthIndicator")
public class SondeFilesTraitement implements HealthIndicator {

    private final List<FileDeTraitement> files;
    private final long profondeurMax;

    public SondeFilesTraitement(List<FileDeTraitement> files,
                                @Value("${ged.supervision.files.profondeur-max:5000}") long profondeurMax) {
        this.files = files;
        this.profondeurMax = profondeurMax;
    }

    @Override
    public Health health() {
        if (files.isEmpty()) {
            return Health.up().withDetail("files", "aucune file de traitement déclarée").build();
        }
        boolean enDefaut = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (FileDeTraitement file : files) {
            try {
                long profondeur = file.profondeur();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("profondeur", profondeur);
                file.ageDuPlusAncien().ifPresent(age -> d.put("agePlusAncienSecondes", age.toSeconds()));
                if (profondeur > profondeurMax) {
                    d.put("anomalie", "profondeur au-delà de " + profondeurMax);
                    enDefaut = true;
                }
                details.put(file.nom(), d);
            } catch (RuntimeException e) {
                details.put(file.nom(), Map.of("anomalie", "file illisible : " + e.getClass().getSimpleName()));
                enDefaut = true;
            }
        }
        return (enDefaut ? Health.down() : Health.up()).withDetails(details).build();
    }
}
