package com.ipt.ged.cycledevie;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Travailleur des traitements de fond du cycle de vie : jobs d'archivage de
 * dossier. Un seul fil par
 * instance ; plusieurs instances se partagent les jobs par bail
 * ({@code FOR UPDATE SKIP LOCKED}). Désactivable
 * ({@code ged.cycledevie.travailleur.actif=false}, profil de test).
 */
@Component
public class PlanificateurCycleDeVie {

    private static final Logger log = LoggerFactory.getLogger(PlanificateurCycleDeVie.class);

    private final ArchivageDossiers archivage;
    private final ProprietesCycleDeVie proprietes;
    private ScheduledExecutorService fil;

    public PlanificateurCycleDeVie(ArchivageDossiers archivage, ProprietesCycleDeVie proprietes) {
        this.archivage = archivage;
        this.proprietes = proprietes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void demarrer() {
        if (!proprietes.getTravailleur().isActif()) {
            log.info("Travailleur du cycle de vie inactif (ged.cycledevie.travailleur.actif=false)");
            return;
        }
        fil = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ged-cycle-de-vie");
            t.setDaemon(true);
            return t;
        });
        long ms = proprietes.getTravailleur().getIntervalle().toMillis();
        fil.scheduleWithFixedDelay(this::tour, ms, ms, TimeUnit.MILLISECONDS);
    }

    /** Un passage : tous les jobs disponibles. */
    void tour() {
        try {
            while (archivage.traiterUnJob()) {
                // job suivant
            }
        } catch (RuntimeException e) {
            log.warn("Travailleur du cycle de vie : passage interrompu", e);
        }
    }

    @PreDestroy
    public void arreter() {
        if (fil != null) fil.shutdownNow();
    }
}
