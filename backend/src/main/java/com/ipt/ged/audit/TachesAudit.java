package com.ipt.ged.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Tâches planifiées du journal d'audit (DAT §7.4.2, §7.4.3) :
 * <ul>
 *   <li>chaque heure : scellement des périodes closes ;</li>
 *   <li>chaque mois : vérification complète de la chaîne (résultat tracé et
 *       exposé en métrique pour l'alerte) ;</li>
 *   <li>chaque mois, et au démarrage : création d'avance des partitions des
 *       prochains mois. Aucune partition n'est jamais supprimée ici : la
 *       rétention de 10 ans se gère par décision de l'Administrateur (P4).</li>
 * </ul>
 * Désactivables par {@code ged.audit.planification.active=false} (tests, ou
 * instance secondaire : une seule instance scelle, un verrou consultatif
 * protège de toute façon contre un double scellement).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ged.audit.planification.active", havingValue = "true", matchIfMissing = true)
public class TachesAudit {

    private static final Logger journal = LoggerFactory.getLogger(TachesAudit.class);

    private final ScellementAudit scellement;
    private final VerificationAudit verification;
    private final JdbcTemplate jdbc;
    private final int moisDAvance;

    public TachesAudit(ScellementAudit scellement, VerificationAudit verification, JdbcTemplate jdbc,
                       @Value("${ged.audit.partitions.mois-d-avance:3}") int moisDAvance) {
        this.scellement = scellement;
        this.verification = verification;
        this.jdbc = jdbc;
        this.moisDAvance = moisDAvance;
    }

    @Scheduled(cron = "${ged.audit.scellement.cron:0 5 * * * *}")
    public void sceller() {
        try {
            int n = scellement.scellerPeriodesCloses().size();
            if (n > 0) journal.info("Journal d'audit : {} période(s) scellée(s)", n);
        } catch (RuntimeException e) {
            // Le scellement sera repris à la prochaine heure ; l'échec doit se voir.
            journal.error("Scellement du journal d'audit en échec", e);
        }
    }

    @Scheduled(cron = "${ged.audit.verification.cron:0 30 3 1 * *}")
    public void verifier() {
        verification.verifier();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${ged.audit.partitions.cron:0 0 1 1 * *}")
    public void creerPartitionsDAvance() {
        YearMonth mois = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= moisDAvance; i++) {
            Instant debut = mois.plusMonths(i).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            jdbc.queryForObject("SELECT journal_audit_creer_partition(?)", String.class, Timestamp.from(debut));
        }
    }
}
