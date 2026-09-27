package com.ipt.ged.ocr.file;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Métriques de la chaîne OCR (§4.3.4, §6.7), aux noms convenus avec le lot
 * exploitation (dev2 : histogramme, seuils et alertes Prometheus) :
 *
 * <ul>
 *   <li>{@code ged.ocr.delai.disponibilite} (timer) : délai entre le dépôt
 *       d'une version et sa disponibilité en recherche ; objectif
 *       {@code GED_OCR_OBJECTIF_DISPONIBILITE}, <b>24 h</b> par défaut (décision
 *       D6 de la revue, au lieu des 5 min du dossier V3) ;</li>
 *   <li>{@code ged.ocr.delai.objectif.depasse} (compteur) : documents rendus
 *       interrogeables au-delà de l'objectif ;</li>
 *   <li>{@code ged.ocr.jobs{issue=termine|reprise|echec}} (compteur).</li>
 * </ul>
 *
 * <p><b>En attendant la fusion du lot exploitation</b>, cette classe publie
 * aussi, sous les noms que ce lot utilisera, la profondeur et l'âge de la file
 * ({@code ged.file.profondeur{file="ocr"}}, {@code ged.file.age.plus.ancien})
 * et l'objectif ({@code ged.ocr.objectif.disponibilite}). Après la fusion, ces
 * trois jauges sont retirées d'ici : {@link FileOcrSupervisee} implémente
 * {@code FileDeTraitement} et le lot exploitation les publie.
 */
public class MetriquesOcr {

    private static final Logger log = LoggerFactory.getLogger(MetriquesOcr.class);

    private final Duration objectif;
    private final Clock horloge;
    private final Timer delai;
    private final Counter depassements;
    private final Counter termines;
    private final Counter reprises;
    private final Counter echecs;

    public MetriquesOcr(MeterRegistry registre, FileOcrSupervisee file, Duration objectif, Clock horloge) {
        this.objectif = objectif;
        this.horloge = horloge;
        this.delai = Timer.builder("ged.ocr.delai.disponibilite")
                .description("Délai entre le dépôt d'une version et sa disponibilité en recherche plein texte")
                .serviceLevelObjectives(objectif)
                .publishPercentiles(0.95)
                .maximumExpectedValue(objectif.multipliedBy(4))
                .register(registre);
        this.depassements = Counter.builder("ged.ocr.delai.objectif.depasse")
                .description("Documents rendus interrogeables au-delà de l'objectif de délai")
                .register(registre);
        this.termines = compteur(registre, "termine");
        this.reprises = compteur(registre, "reprise");
        this.echecs = compteur(registre, "echec");
        // Jauges provisoires (voir la note de classe).
        Gauge.builder("ged.ocr.objectif.disponibilite", objectif, d -> d.toMillis() / 1000.0)
                .baseUnit("seconds")
                .description("Objectif de délai dépôt → recherche (décision D6 : 24 h)")
                .register(registre);
        if (file != null) {
            Tags tags = Tags.of("file", file.nom());
            Gauge.builder("ged.file.profondeur", file, FileOcrSupervisee::profondeur)
                    .tags(tags)
                    .description("Traitements en attente ou en cours")
                    .register(registre);
            Gauge.builder("ged.file.age.plus.ancien", file,
                            f -> f.ageDuPlusAncien().map(d -> d.toMillis() / 1000.0).orElse(0.0))
                    .tags(tags)
                    .baseUnit("seconds")
                    .description("Ancienneté du plus ancien traitement en attente")
                    .register(registre);
        }
    }

    private static Counter compteur(MeterRegistry registre, String issue) {
        return Counter.builder("ged.ocr.jobs").tag("issue", issue)
                .description("Issues des exécutions de jobs OCR").register(registre);
    }

    /** Document devenu interrogeable : mesure le délai depuis son dépôt. */
    public Duration disponible(Instant deposeLe) {
        Duration d = Duration.between(deposeLe, horloge.instant());
        if (d.isNegative()) d = Duration.ZERO;
        delai.record(d);
        termines.increment();
        if (d.compareTo(objectif) > 0) {
            depassements.increment();
            log.warn("Objectif de disponibilité dépassé : document interrogeable {} h après son dépôt (objectif {} h)",
                    d.toHours(), objectif.toHours());
        }
        return d;
    }

    public void reprise() {
        reprises.increment();
    }

    public void echec() {
        echecs.increment();
    }

    public Duration objectif() {
        return objectif;
    }
}
