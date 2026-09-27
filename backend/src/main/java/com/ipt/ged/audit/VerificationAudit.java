package com.ipt.ged.audit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vérification du scellement (DAT §7.4.2) : recalcule toute la chaîne depuis
 * les enregistrements présents en base et la compare aux scellements inscrits
 * en base <b>et</b> à ceux exportés hors base.
 *
 * <p>Anomalies détectées :
 * <ul>
 *   <li>{@code EMPREINTE_DIFFERENTE} : un enregistrement d'une période scellée a
 *       été modifié, supprimé ou ajouté après coup ;</li>
 *   <li>{@code CHAINE_ROMPUE} : le scellement ne désigne pas le précédent ;</li>
 *   <li>{@code PERIODE_MANQUANTE} : un trou entre deux scellements ;</li>
 *   <li>{@code EXPORT_ABSENT} / {@code EXPORT_DIFFERENT} : la copie hors base
 *       manque ou ne concorde pas (scellement réécrit en base) ;</li>
 *   <li>{@code SCELLEMENT_SUPPRIME} : un scellement exporté n'existe plus en base.</li>
 * </ul>
 * Le résultat est lui-même tracé au journal ({@code AUDIT_VERIFIE}) et exposé en
 * métrique ({@code ged_audit_anomalies}) pour l'alerte d'exploitation.
 */
@Service
public class VerificationAudit {

    private static final Logger journal = LoggerFactory.getLogger(VerificationAudit.class);

    private final JdbcTemplate jdbc;
    private final ScellementAudit scellement;
    private final AuditService audit;
    private final AtomicReference<Double> dernieresAnomalies = new AtomicReference<>(Double.NaN);

    public VerificationAudit(JdbcTemplate jdbc, ScellementAudit scellement, AuditService audit,
                             MeterRegistry registre) {
        this.jdbc = jdbc;
        this.scellement = scellement;
        this.audit = audit;
        Gauge.builder("ged.audit.anomalies", dernieresAnomalies, AtomicReference::get)
                .description("Anomalies relevées par la dernière vérification du scellement du journal d'audit")
                .register(registre);
        // Retard du scellement : une tâche horaire arrêtée se voit avant la vérification mensuelle.
        Gauge.builder("ged.audit.scellement.retard", this, VerificationAudit::retardScellement)
                .description("Temps écoulé depuis la fin de la dernière période scellée")
                .baseUnit("seconds").register(registre);
    }

    double retardScellement() {
        try {
            Timestamp fin = jdbc.queryForObject("SELECT max(periode_fin) FROM journal_audit_scellement", Timestamp.class);
            return fin == null ? Double.NaN : (Instant.now().toEpochMilli() - fin.getTime()) / 1000.0;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    public record Anomalie(String type, Instant periodeDebut, String detail) {}

    public record Rapport(Instant verifieLe, int periodesVerifiees, long enregistrementsVerifies,
                          Instant scelleJusquA, List<Anomalie> anomalies) {
        public boolean integre() {
            return anomalies.isEmpty();
        }
    }

    /** Vérifie la chaîne complète, trace le résultat et le rend. */
    public Rapport verifier() {
        Rapport rapport = calculer();
        dernieresAnomalies.set((double) rapport.anomalies().size());
        String resume = rapport.integre()
                ? "Chaîne intègre : " + rapport.periodesVerifiees() + " périodes, "
                  + rapport.enregistrementsVerifies() + " enregistrements"
                : rapport.anomalies().size() + " anomalie(s) : " + rapport.anomalies().get(0).type()
                  + " sur la période " + rapport.anomalies().get(0).periodeDebut();
        if (rapport.integre()) {
            journal.info("Vérification du journal d'audit : {}", resume);
        } else {
            journal.error("Vérification du journal d'audit en ÉCHEC : {} — {}", resume, rapport.anomalies());
        }
        EntreeAudit trace = EntreeAudit.de(ActionAudit.AUDIT_VERIFIE)
                .avecApres(Map.of("periodesVerifiees", rapport.periodesVerifiees(),
                        "enregistrementsVerifies", rapport.enregistrementsVerifies(),
                        "anomalies", rapport.anomalies().size()))
                .avecMotif(resume);
        audit.enregistrerHorsTransaction(rapport.integre() ? trace : trace.echec(resume));
        return rapport;
    }

    Rapport calculer() {
        List<Map<String, Object>> scelles = jdbc.queryForList("""
                SELECT periode_debut, periode_fin, nombre, empreinte_precedente, empreinte
                  FROM journal_audit_scellement ORDER BY periode_debut""");
        Map<String, Set<String>> exportes = new HashMap<>();
        List<Anomalie> anomalies = new ArrayList<>();
        try {
            for (Map<String, Object> ligne : scellement.lireExport()) {
                if (ligne.containsKey("illisible")) {
                    anomalies.add(new Anomalie("EXPORT_ILLISIBLE", null, String.valueOf(ligne.get("illisible"))));
                    continue;
                }
                exportes.computeIfAbsent(String.valueOf(ligne.get("periodeDebut")), k -> new HashSet<>())
                        .add(String.valueOf(ligne.get("empreinte")));
            }
        } catch (IOException e) {
            anomalies.add(new Anomalie("EXPORT_ILLISIBLE", null, e.getMessage()));
        }

        String precedente = ScellementAudit.EMPREINTE_INITIALE;
        Instant finPrecedente = null;
        long enregistrements = 0;
        Set<String> vus = new HashSet<>();
        for (Map<String, Object> s : scelles) {
            Instant debut = ((Timestamp) s.get("periode_debut")).toInstant();
            Instant fin = ((Timestamp) s.get("periode_fin")).toInstant();
            String empreinteBase = ((String) s.get("empreinte")).trim();
            String precedenteBase = ((String) s.get("empreinte_precedente")).trim();
            vus.add(debut.toString());

            if (finPrecedente != null && !finPrecedente.equals(debut)) {
                anomalies.add(new Anomalie("PERIODE_MANQUANTE", finPrecedente, "trou jusqu'à " + debut));
            }
            if (!precedente.equals(precedenteBase)) {
                anomalies.add(new Anomalie("CHAINE_ROMPUE", debut, "précédente attendue " + precedente));
            }
            ScellementAudit.Scellement recalcule = scellement.calculer(debut, fin, precedenteBase);
            enregistrements += recalcule.nombre();
            if (!recalcule.empreinte().equals(empreinteBase)) {
                anomalies.add(new Anomalie("EMPREINTE_DIFFERENTE", debut,
                        "base " + empreinteBase + ", recalculée " + recalcule.empreinte()
                                + " (" + recalcule.nombre() + " enregistrements, " + s.get("nombre") + " scellés)"));
            }
            Set<String> copies = exportes.get(debut.toString());
            if (copies == null) {
                anomalies.add(new Anomalie("EXPORT_ABSENT", debut, "aucune copie hors base"));
            } else if (!copies.contains(empreinteBase) || copies.size() > 1) {
                anomalies.add(new Anomalie("EXPORT_DIFFERENT", debut, "hors base " + copies + ", base " + empreinteBase));
            }
            precedente = empreinteBase;
            finPrecedente = fin;
        }
        for (String periode : exportes.keySet()) {
            if (!vus.contains(periode)) {
                anomalies.add(new Anomalie("SCELLEMENT_SUPPRIME", Instant.parse(periode),
                        "présent hors base, absent de journal_audit_scellement"));
            }
        }
        return new Rapport(Instant.now(), scelles.size(), enregistrements, finPrecedente, List.copyOf(anomalies));
    }
}
