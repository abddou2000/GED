package com.ipt.ged.document.conservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Déclenchement quotidien de l'alerte d'échéance de conservation (§12.9).
 * Paramétrage : {@code ged.conservation.alertes.actif} (activation) et
 * {@code ged.conservation.alertes.cron} (heure, fuseau de MMED ; 6 h par
 * défaut). Toutes les instances peuvent planifier la tâche : le verrou de
 * tâche garantit qu'une seule l'exécute à la fois.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ged.conservation.alertes.actif", havingValue = "true", matchIfMissing = true)
public class TacheAlertesEcheance {

    private static final Logger journal = LoggerFactory.getLogger(TacheAlertesEcheance.class);

    private final AlertesEcheanceConservation alertes;

    public TacheAlertesEcheance(AlertesEcheanceConservation alertes) {
        this.alertes = alertes;
    }

    @Scheduled(cron = "${ged.conservation.alertes.cron:0 0 6 * * *}", zone = "Africa/Casablanca")
    public void executer() {
        try {
            alertes.executer();
        } catch (RuntimeException e) {
            // Reprise au passage suivant (les documents non marqués restent à signaler) ;
            // l'échec doit se voir au journal technique.
            journal.error("Alerte d'échéance de conservation en échec", e);
        }
    }
}
