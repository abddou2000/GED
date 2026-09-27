package com.ipt.ged.ocr.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Fait tourner N workers OCR (nombre paramétrable, §4.3.4 « le nombre de
 * workers est paramétrable ») : chacun réserve un job, le traite, recommence ;
 * file vide, il attend l'intervalle de scrutation. Plusieurs instances de
 * l'application peuvent faire tourner leurs pools sur la même file.
 */
public class PoolTravailleursOcr implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PoolTravailleursOcr.class);

    private final int nombre;
    private final Duration scrutation;
    private final IntFunction<TravailleurOcr> fabrique;
    private ExecutorService executeur;
    private volatile boolean actif;

    public PoolTravailleursOcr(int nombre, Duration scrutation, IntFunction<TravailleurOcr> fabrique) {
        this.nombre = nombre;
        this.scrutation = scrutation;
        this.fabrique = fabrique;
    }

    @Override
    public synchronized void start() {
        if (actif || nombre <= 0) return;
        actif = true;
        executeur = Executors.newFixedThreadPool(nombre, r -> {
            Thread t = new Thread(r);
            t.setName("ocr-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        List<TravailleurOcr> travailleurs = new ArrayList<>();
        for (int i = 0; i < nombre; i++) travailleurs.add(fabrique.apply(i));
        travailleurs.forEach(t -> executeur.execute(() -> boucle(t)));
        log.info("{} worker(s) OCR démarré(s), scrutation toutes les {} s", nombre, scrutation.toSeconds());
    }

    private void boucle(TravailleurOcr t) {
        while (actif && !Thread.currentThread().isInterrupted()) {
            boolean travail;
            try {
                travail = t.traiterUn();
            } catch (RuntimeException e) {
                // Base momentanément injoignable : on réessaie au prochain tour.
                log.error("Worker {} : file OCR inaccessible", t.nom(), e);
                travail = false;
            }
            if (!travail) {
                try {
                    Thread.sleep(scrutation.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public synchronized void stop() {
        actif = false;
        if (executeur != null) {
            executeur.shutdownNow();
            try {
                executeur.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return actif;
    }
}
