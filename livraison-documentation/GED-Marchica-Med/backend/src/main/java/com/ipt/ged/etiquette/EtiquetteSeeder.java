package com.ipt.ged.etiquette;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration : quelques étiquettes colorées courantes.
 * S'exécute sur base vide, hors profil de test.
 */
@Component
@Order(8)
@Profile("!test")
public class EtiquetteSeeder implements CommandLineRunner {

    private final EtiquetteRepository repo;

    public EtiquetteSeeder(EtiquetteRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        repo.saveAll(List.of(
                new Etiquette("TAG-URG", "Urgent", "#c0392b"),
                new Etiquette("TAG-CONF", "Confidentiel", "#9e1b32"),
                new Etiquette("TAG-VERIF", "À vérifier", "#b0812e"),
                new Etiquette("TAG-OK", "Validé", "#1e7a46")
        ));
    }
}
