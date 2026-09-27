package com.ipt.ged.identite.annuaire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * partagent le même annuaire au lieu de se disputer le port.
 */
@Component
@Profile({"dev", "test"})
public class AnnuaireEmbarque {

    private static final Logger log = LoggerFactory.getLogger(AnnuaireEmbarque.class);
    private static final Map<Integer, SimulateurAnnuaire> DEMARRES = new ConcurrentHashMap<>();

    private final SimulateurAnnuaire simulateur;

    public AnnuaireEmbarque(Environment environnement, ResourceLoader ressources,
                            @Value("${ged.identite.annuaire.embarque.base:DC=marchicamed,DC=ma}") String base,
                            @Value("${ged.identite.annuaire.embarque.port}") int port,
                            @Value("${ged.identite.annuaire.embarque.ldif}") String ldif) {
        boolean exploitation = Arrays.stream(environnement.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("uat"));
        if (exploitation) {
            throw new IllegalStateException("Annuaire de démonstration interdit avec les profils prod et uat.");
        }
        this.simulateur = DEMARRES.computeIfAbsent(port, p -> {
            try (InputStream in = ressources.getResource(ldif).getInputStream()) {
                SimulateurAnnuaire s = SimulateurAnnuaire.demarrer(base, p, in);
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
}
