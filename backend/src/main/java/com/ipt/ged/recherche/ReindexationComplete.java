package com.ipt.ged.recherche;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Réindexation complète (§4.4.1) : opération d'administration, en tâche de
 * fond, avec suivi de progression.
 *
 * <p>Nécessaire après un changement de configuration linguistique (par
 * exemple un ajustement de la normalisation de l'arabe) : le vecteur de chaque
 * document est recalculé à partir du texte déjà extrait, <b>sans refaire
 * l'OCR</b>. Le travail avance par lots courts, chacun dans sa propre
 * transaction : la recherche reste disponible, chaque document gardant son
 * ancien vecteur jusqu'à son recalcul. Une pause entre lots ménage la base
 * pendant les heures d'activité du bureau d'ordre.
 */
public class ReindexationComplete {

    private static final Logger log = LoggerFactory.getLogger(ReindexationComplete.class);

    public enum Etat { INACTIVE, EN_COURS, TERMINEE, ECHOUEE }

    public record Progression(Etat etat, long total, long traites, Instant demarreeLe, Instant termineeLe,
                              String erreur) {
        public int pourcentage() {
            return total <= 0 ? (etat == Etat.TERMINEE ? 100 : 0) : (int) Math.min(100, traites * 100 / total);
        }
    }

    private final SearchIndexer indexer;
    private final Executor executeur;
    private final int taileLot;
    private final Duration pause;
    private final AtomicBoolean enCours = new AtomicBoolean();
    private final AtomicReference<Progression> progression =
            new AtomicReference<>(new Progression(Etat.INACTIVE, 0, 0, null, null, null));

    public ReindexationComplete(SearchIndexer indexer, Executor executeur, int tailleLot, Duration pause) {
        this.indexer = indexer;
        this.executeur = executeur;
        this.taileLot = tailleLot;
        this.pause = pause;
    }

    /** @return {@code false} si une réindexation est déjà en cours. */
    public boolean demarrer() {
        if (!enCours.compareAndSet(false, true)) return false;
        Instant debut = Instant.now();
        long total;
        try {
            total = indexer.compter();
        } catch (RuntimeException e) {
            enCours.set(false);
            throw e;
        }
        progression.set(new Progression(Etat.EN_COURS, total, 0, debut, null, null));
        executeur.execute(() -> executer(total, debut));
        return true;
    }

    public Progression progression() {
        return progression.get();
    }

    private void executer(long total, Instant debut) {
        long traites = 0;
        UUID curseur = null;
        try {
            log.info("Réindexation complète : début, {} document(s)", total);
            List<UUID> lot;
            do {
                lot = indexer.reindexerLot(curseur, taileLot);
                if (!lot.isEmpty()) {
                    curseur = lot.get(lot.size() - 1);
                    traites += lot.size();
                    progression.set(new Progression(Etat.EN_COURS, Math.max(total, traites), traites, debut, null, null));
                    if (!pause.isZero()) Thread.sleep(pause.toMillis());
                }
            } while (lot.size() == taileLot);
            progression.set(new Progression(Etat.TERMINEE, Math.max(total, traites), traites, debut, Instant.now(), null));
            log.info("Réindexation complète : terminée, {} document(s)", traites);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progression.set(new Progression(Etat.ECHOUEE, total, traites, debut, Instant.now(), "interrompue"));
        } catch (RuntimeException e) {
            log.error("Réindexation complète interrompue après {} document(s)", traites, e);
            progression.set(new Progression(Etat.ECHOUEE, total, traites, debut, Instant.now(), e.getMessage()));
        } finally {
            enCours.set(false);
        }
    }
}
