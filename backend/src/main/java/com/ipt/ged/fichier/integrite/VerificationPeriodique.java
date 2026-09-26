package com.ipt.ged.fichier.integrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vérification mensuelle de tout le fonds (§6.1.4).
 *
 * <p>Désactivée par défaut ({@code ged.fichiers.integrite.verification-planifiee})
 * tant qu'aucune {@link SourceEmpreintes} n'est branchée sur les versions de
 * document ; la planification ({@code ged.fichiers.integrite.cron}, le 1er du
 * mois à 3 h par défaut) se règle sans recompiler.
 */
public class VerificationPeriodique {

    private static final Logger log = LoggerFactory.getLogger(VerificationPeriodique.class);

    private final VerificationIntegrite verification;
    private final SourceEmpreintes source;
    /** Une passe longue ne doit pas en chevaucher une autre (déclenchement manuel pendant la tâche). */
    private final AtomicBoolean enCours = new AtomicBoolean();

    public VerificationPeriodique(VerificationIntegrite verification, SourceEmpreintes source) {
        this.verification = verification;
        this.source = source;
    }

    @Scheduled(cron = "${ged.fichiers.integrite.cron:0 0 3 1 * *}")
    public void planifiee() {
        executer();
    }

    /** @return décompte par statut, ou vide si une passe est déjà en cours. */
    public Map<VerificationIntegrite.Statut, Integer> executer() {
        Map<VerificationIntegrite.Statut, Integer> compte = new EnumMap<>(VerificationIntegrite.Statut.class);
        if (!enCours.compareAndSet(false, true)) {
            log.warn("Vérification d'intégrité déjà en cours : passe ignorée");
            return compte;
        }
        try {
            log.info("Vérification d'intégrité du fonds : début");
            source.parcourir(e -> compte.merge(
                    verification.verifier(e.fichierId(), e.empreinte(), e.reference()).statut(), 1, Integer::sum));
            log.info("Vérification d'intégrité du fonds : fin, {}", compte);
            return compte;
        } finally {
            enCours.set(false);
        }
    }
}
