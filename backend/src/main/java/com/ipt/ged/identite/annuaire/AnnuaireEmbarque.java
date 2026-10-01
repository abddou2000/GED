package com.ipt.ged.identite.annuaire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Annuaire de démonstration pour le poste de développement et les tests : un
 * {@link SimulateurAnnuaire} chargé d'un fichier LDIF, démarré au lancement.
 *
 * <p><b>Jamais en production ni en recette.</b> Le composant n'existe que dans
 * les profils {@code dev} et {@code test}, et il refuse de démarrer si
 * {@code prod} ou {@code uat} est aussi actif : un compte de démonstration à mot
 * de passe connu ne doit pas pouvoir ouvrir une session sur une vraie GED.
 *
 * <p>Un seul serveur par port et par JVM : plusieurs contextes Spring de tests
 * partagent le même annuaire au lieu de se disputer le port. Chaque contexte le
 * rend à sa fermeture et le dernier l'arrête : le fil d'écoute LDAP n'est pas un
 * fil démon, et tant qu'il tournait, une JVM dont le démarrage avait été refusé
 * (configuration invalide) ne s'arrêtait jamais (observation O1, recette vague 9).
 */
@Component
@Profile({"dev", "test"})
public class AnnuaireEmbarque implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AnnuaireEmbarque.class);

    /** Simulateur démarré sur un port, et nombre de contextes qui s'en servent. */
    private static final class Partage {
        private final SimulateurAnnuaire simulateur;
        private int contextes;

        private Partage(SimulateurAnnuaire simulateur) {
            this.simulateur = simulateur;
        }
    }

    /** Par port ; lu et modifié sous le verrou de la classe. */
    private static final Map<Integer, Partage> DEMARRES = new HashMap<>();

    private final int port;
    private final SimulateurAnnuaire simulateur;
    private boolean rendu;

    public AnnuaireEmbarque(Environment environnement, ResourceLoader ressources,
                            @Value("${ged.identite.annuaire.embarque.base:DC=marchicamed,DC=ma}") String base,
                            @Value("${ged.identite.annuaire.embarque.port}") int port,
                            @Value("${ged.identite.annuaire.embarque.ldif}") String ldif) {
        boolean exploitation = Arrays.stream(environnement.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("uat"));
        if (exploitation) {
            throw new IllegalStateException("Annuaire de démonstration interdit avec les profils prod et uat.");
        }
        this.port = port;
        this.simulateur = prendre(port, () -> {
            try (InputStream in = ressources.getResource(ldif).getInputStream()) {
                SimulateurAnnuaire s = SimulateurAnnuaire.demarrer(base, port, in);
                log.warn("Annuaire de DÉMONSTRATION démarré sur {} ({}) : comptes à mot de passe connu, "
                        + "profils dev et test uniquement.", s.url(), ldif);
                return s;
            } catch (Exception e) {
                throw new IllegalStateException("Démarrage de l'annuaire de démonstration impossible", e);
            }
        });
    }

    public SimulateurAnnuaire simulateur() {
        return simulateur;
    }

    /**
     * Fermeture du contexte, arrêt normal ou démarrage refusé : l'annuaire est
     * rendu, et arrêté si plus aucun contexte ne s'en sert.
     */
    @Override
    public void destroy() {
        synchronized (AnnuaireEmbarque.class) {
            if (rendu) return;
            rendu = true;
            Partage p = DEMARRES.get(port);
            if (p == null || p.simulateur != simulateur || --p.contextes > 0) return;
            DEMARRES.remove(port);
            p.simulateur.arreter();
            log.info("Annuaire de démonstration arrêté (port {}).", port);
        }
    }

    private static SimulateurAnnuaire prendre(int port, Supplier<SimulateurAnnuaire> demarrer) {
        synchronized (AnnuaireEmbarque.class) {
            Partage p = DEMARRES.get(port);
            if (p == null) {
                p = new Partage(demarrer.get());
                DEMARRES.put(port, p);
            }
            p.contextes++;
            return p.simulateur;
        }
    }
}
