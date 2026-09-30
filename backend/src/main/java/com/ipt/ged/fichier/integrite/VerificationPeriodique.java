package com.ipt.ged.fichier.integrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vérification de tout le fonds (§6.1.4) : passe mensuelle planifiée
 * ({@code ged.fichiers.integrite.verification-planifiee}, planification
 * {@code ged.fichiers.integrite.cron}, le 1er du mois à 3 h par défaut) ou
 * déclenchée à la demande par l'Administrateur ({@link VerificationALaDemande}).
 *
 * <p>Une seule passe à la fois : un déclenchement pendant une passe en cours
 * est ignoré. L'état de la dernière passe (début, fin, décompte par statut)
 * est gardé en mémoire pour la supervision.
 */
public class VerificationPeriodique {

    private static final Logger log = LoggerFactory.getLogger(VerificationPeriodique.class);

    private final VerificationIntegrite verification;
    private final SourceEmpreintes source;
    /** Une passe longue ne doit pas en chevaucher une autre (déclenchement manuel pendant la tâche). */
    private final AtomicBoolean enCours = new AtomicBoolean();
    private volatile Etat etat = new Etat(false, null, null, Map.of());

    public VerificationPeriodique(VerificationIntegrite verification, SourceEmpreintes source) {
        this.verification = verification;
        this.source = source;
    }

    /** @return décompte par statut, ou vide si une passe est déjà en cours. */
    public Map<VerificationIntegrite.Statut, Integer> executer() {
        if (!enCours.compareAndSet(false, true)) {
            log.warn("Vérification d'intégrité déjà en cours : passe ignorée");
            return new EnumMap<>(VerificationIntegrite.Statut.class);
        }
        return passe();
    }

    /**
     * Lance une passe dans un fil dédié (le fonds entier peut demander des
     * heures) ; {@code false} si une passe est déjà en cours.
     */
    public boolean demarrerEnFond() {
        if (!enCours.compareAndSet(false, true)) return false;
        etat = new Etat(true, Instant.now(), null, Map.of());
        Thread fil = new Thread(this::passe, "ged-verification-integrite");
        fil.setDaemon(true);
        fil.start();
        return true;
    }

    /** État de la passe en cours ou de la dernière passe terminée. */
    public Etat etat() {
        return etat;
    }

    private Map<VerificationIntegrite.Statut, Integer> passe() {
        Map<VerificationIntegrite.Statut, Integer> compte = new EnumMap<>(VerificationIntegrite.Statut.class);
        Instant debut = Instant.now();
        etat = new Etat(true, debut, null, Map.of());
        try {
            log.info("Vérification d'intégrité du fonds : début");
            source.parcourir(e -> compte.merge(
                    verification.verifier(e.fichierId(), e.empreinte(), e.reference()).statut(), 1, Integer::sum));
            log.info("Vérification d'intégrité du fonds : fin, {}", compte);
            return compte;
        } finally {
            etat = new Etat(false, debut, Instant.now(), Map.copyOf(compte));
            enCours.set(false);
        }
    }

    /**
     * @param enCours vrai pendant une passe ;
     * @param debut   début de la passe en cours ou de la dernière ; absent si aucune ;
     * @param fin     fin de la dernière passe ; absent pendant une passe ;
     * @param bilan   décompte par statut de la dernière passe terminée.
     */
    public record Etat(boolean enCours, Instant debut, Instant fin, Map<VerificationIntegrite.Statut, Integer> bilan) {
    }
}
