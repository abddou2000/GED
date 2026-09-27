package com.ipt.ged.supervision;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.util.List;

/**
 * Indicateurs propres à la GED exposés sur {@code /actuator/prometheus}
 * (DAT 6.7), en plus de ceux que Spring Boot fournit déjà (temps de réponse
 * et codes HTTP de l'API, JVM, pool de connexions) :
 * <ul>
 *   <li>{@code ged_sante{composant}} : 1 si la sonde est UP, 0 sinon — c'est
 *       sur elle que porte l'alerte « sonde indisponible plus de 2 minutes » ;</li>
 *   <li>{@code ged_stockage_libre_bytes} et {@code ged_stockage_total_bytes} :
 *       volume du référentiel de fichiers, pour l'alerte à 80 % ;</li>
 *   <li>{@code ged_file_profondeur{file}} et {@code ged_file_age_plus_ancien_seconds{file}}
 *       pour chaque {@link FileDeTraitement} déclarée ;</li>
 *   <li>{@code ged_ocr_objectif_disponibilite_seconds} : l'objectif de délai
 *       entre dépôt et disponibilité en recherche (24 h, décision D6). Publié
 *       comme métrique pour que le seuil d'alerte se règle dans la
 *       configuration de l'application, et non en dur dans Prometheus.</li>
 * </ul>
 */
@Component
public class MetriquesSupervision implements MeterBinder {

    /** Sondes suivies par {@code ged_sante} ; les noms sont ceux de /actuator/health. */
    static final List<String> SONDES = List.of("db", "referentielFichiers", "annuaire", "antivirus", "filesTraitement");

    private final ObjectProvider<HealthEndpoint> sante;
    private final SondeReferentielFichiers referentiel;
    private final List<FileDeTraitement> files;
    private final Duration objectifOcr;

    public MetriquesSupervision(ObjectProvider<HealthEndpoint> sante,
                                SondeReferentielFichiers referentiel,
                                List<FileDeTraitement> files,
                                @Value("${ged.supervision.ocr.objectif-disponibilite:24h}") Duration objectifOcr) {
        this.sante = sante;
        this.referentiel = referentiel;
        this.files = files;
        this.objectifOcr = objectifOcr;
    }

    @Override
    public void bindTo(MeterRegistry registre) {
        for (String sonde : SONDES) {
            Gauge.builder("ged.sante", this, m -> m.etat(sonde))
                    .description("Etat de la sonde de sante : 1 = UP, 0 = indisponible")
                    .tag("composant", sonde)
                    .register(registre);
        }

        Gauge.builder("ged.stockage.libre", this, m -> m.espace(true))
                .description("Espace libre du volume du referentiel de fichiers")
                .baseUnit("bytes").tag("referentiel", "fichiers").register(registre);
        Gauge.builder("ged.stockage.total", this, m -> m.espace(false))
                .description("Taille du volume du referentiel de fichiers")
                .baseUnit("bytes").tag("referentiel", "fichiers").register(registre);

        Gauge.builder("ged.ocr.objectif.disponibilite", objectifOcr, d -> d.toMillis() / 1000.0)
                .description("Objectif de delai entre depot et disponibilite en recherche")
                .baseUnit("seconds").register(registre);

        for (FileDeTraitement file : files) {
            Gauge.builder("ged.file.profondeur", file, f -> sure(f::profondeur))
                    .description("Traitements en attente ou en cours dans la file")
                    .tag("file", file.nom()).register(registre);
            Gauge.builder("ged.file.age.plus.ancien", file,
                            f -> sure(() -> f.ageDuPlusAncien().map(a -> a.toMillis() / 1000.0).orElse(0.0)))
                    .description("Age du plus ancien traitement en attente")
                    .baseUnit("seconds").tag("file", file.nom()).register(registre);
        }
    }

    double etat(String sonde) {
        HealthEndpoint endpoint = sante.getIfAvailable();
        if (endpoint == null) return Double.NaN;
        HealthComponent composant = endpoint.healthForPath(sonde);
        // Sonde absente de ce déploiement : NaN, que Prometheus ignore, plutôt
        // qu'un 0 qui déclencherait une fausse alerte.
        if (composant == null) return Double.NaN;
        return Status.UP.equals(composant.getStatus()) ? 1.0 : 0.0;
    }

    double espace(boolean libre) {
        try {
            var volume = Files.getFileStore(referentiel.racine());
            return libre ? volume.getUsableSpace() : volume.getTotalSpace();
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
    }

    /* Une file illisible donne NaN : la sonde filesTraitement passe DOWN et
       c'est elle qui alerte, sans faire échouer toute la collecte. */
    private static double sure(java.util.function.Supplier<Number> lecture) {
        try {
            return lecture.get().doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
