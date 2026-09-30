package com.ipt.ged.fichier.integrite;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supervision de l'intégrité des fichiers (§6.1.4, T-059, ANO-E5-005) : une
 * divergence détectée produit, en plus de l'événement d'audit
 * {@code INTEGRITE_ANOMALIE}, une <b>métrique</b> que la règle Prometheus
 * {@code GedIntegriteFichiersAnomalie} (deploiement/prometheus/alertes.yml)
 * transforme en alerte.
 *
 * <ul>
 *   <li>{@code ged_integrite_anomalies_total{statut}} : compteur de toute
 *       divergence détectée (passe mensuelle, passe ou vérification à la
 *       demande), par statut ({@code EMPREINTE_DIVERGENTE}, {@code ALTERE},
 *       {@code ABSENT}, {@code ILLISIBLE}) ; publié à 0 dès le démarrage ;</li>
 *   <li>{@code ged_integrite_derniere_passe_anomalies} : fichiers non conformes
 *       de la dernière passe <b>terminée</b> sur tout le fonds (NaN tant
 *       qu'aucune passe n'a abouti depuis le démarrage) : l'alerte persiste
 *       jusqu'à une passe conforme, au lieu de s'éteindre avec la fenêtre du
 *       compteur.</li>
 * </ul>
 */
public class MetriquesIntegrite {

    public static final String ANOMALIES = "ged.integrite.anomalies";
    public static final String DERNIERE_PASSE = "ged.integrite.derniere.passe.anomalies";

    private final Map<VerificationIntegrite.Statut, Counter> compteurs = new EnumMap<>(VerificationIntegrite.Statut.class);
    private final VerificationPeriodique fonds;
    /** Résultat de la dernière passe terminée, gardé pendant la passe suivante. */
    private final AtomicReference<Double> derniere = new AtomicReference<>(Double.NaN);

    public MetriquesIntegrite(MeterRegistry registre, VerificationPeriodique fonds) {
        this.fonds = fonds;
        for (VerificationIntegrite.Statut s : VerificationIntegrite.Statut.values()) {
            if (s == VerificationIntegrite.Statut.CONFORME) continue;
            compteurs.put(s, Counter.builder(ANOMALIES).tag("statut", s.name())
                    .description("Anomalies d'intégrité de fichier détectées (empreinte, authentification GCM, absence, lecture)")
                    .register(registre));
        }
        Gauge.builder(DERNIERE_PASSE, this, MetriquesIntegrite::dernierePasse)
                .description("Fichiers non conformes relevés par la dernière passe de vérification du fonds terminée")
                .register(registre);
    }

    /** Toute divergence publiée par {@link VerificationIntegrite}, quelle que soit la passe. */
    @EventListener
    public void anomalie(VerificationIntegrite.AnomalieIntegrite a) {
        Counter c = compteurs.get(a.statut());
        if (c != null) c.increment();
    }

    double dernierePasse() {
        VerificationPeriodique.Etat e = fonds.etat();
        if (!e.enCours() && e.fin() != null) {
            derniere.set((double) e.bilan().entrySet().stream()
                    .filter(x -> x.getKey() != VerificationIntegrite.Statut.CONFORME)
                    .mapToInt(Map.Entry::getValue).sum());
        }
        return derniere.get();
    }
}
