package com.ipt.ged.ocr.file;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Métriques de la chaîne OCR (§4.3.4, §6.7), exposées par Micrometer :
 *
 * <ul>
 *   <li>{@code ocr_delai_disponibilite} (timer) : délai constaté entre le dépôt
 *       d'une version et sa disponibilité en recherche, avec la borne
 *       d'objectif comme seuil d'histogramme (part des documents dans
 *       l'objectif) et le 95e centile ;</li>
 *   <li>{@code ocr_delai_disponibilite_objectif_secondes} (jauge) : l'objectif
 *       configuré — <b>24 h</b> (décision D6 de la revue, au lieu des 5 min
 *       du dossier V3), pour que la règle d'alerte le lise au lieu de le recopier ;</li>
 *   <li>{@code ocr_delai_objectif_depasse_total} (compteur) : documents rendus
 *       interrogeables au-delà de l'objectif ;</li>
 *   <li>{@code ocr_file_profondeur} (jauge) : jobs en attente ou en cours ;</li>
 *   <li>{@code ocr_file_age_plus_ancien_secondes} (jauge) : ancienneté du plus
 *       vieux dépôt non traité — alerte <i>avant</i> que l'objectif soit
 *       dépassé ;</li>
 *   <li>{@code ocr_jobs_total{issue=termine|reprise|echec}} (compteur).</li>
 * </ul>
 * Les jauges interrogent la base à chaque collecte : elles ne sont
 * enregistrées que si la chaîne est active (la table doit exister).
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

    public MetriquesOcr(MeterRegistry registre, OcrJobQueue file, Duration objectif, Clock horloge) {
        this.objectif = objectif;
        this.horloge = horloge;
        this.delai = Timer.builder("ocr_delai_disponibilite")
                .description("Délai entre le dépôt d'une version et sa disponibilité en recherche plein texte")
                .serviceLevelObjectives(objectif)
                .publishPercentiles(0.95)
                .publishPercentileHistogram(false)
                .maximumExpectedValue(objectif.multipliedBy(4))
                .register(registre);
        this.depassements = Counter.builder("ocr_delai_objectif_depasse")
                .description("Documents rendus interrogeables au-delà de l'objectif de délai")
                .register(registre);
        this.termines = compteur(registre, "termine");
        this.reprises = compteur(registre, "reprise");
        this.echecs = compteur(registre, "echec");
        Gauge.builder("ocr_delai_disponibilite_objectif_secondes", () -> objectif.toSeconds())
                .description("Objectif de délai dépôt → recherche (décision D6 : 24 h)")
                .register(registre);
        if (file != null) {
            Gauge.builder("ocr_file_profondeur", file, f -> f.profondeur())
                    .description("Jobs OCR en attente ou en cours")
                    .register(registre);
            Gauge.builder("ocr_file_age_plus_ancien_secondes", file,
                            f -> f.plusAncienDepotEnAttente()
                                    .map(t -> (double) Duration.between(t, horloge.instant()).toSeconds())
                                    .orElse(0.0))
                    .description("Ancienneté du plus ancien dépôt pas encore interrogeable")
                    .register(registre);
        }
    }

    private static Counter compteur(MeterRegistry registre, String issue) {
        return Counter.builder("ocr_jobs").tag("issue", issue)
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
