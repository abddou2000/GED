package com.ipt.ged.employe;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Jeu de données de démonstration : quelques employés, pour pouvoir tester
 * la création de circuits de workflow (choix des approbateurs) dès le démarrage.
 * Ne s'exécute que si la table est vide.
 */
@Component
@Order(1)
public class EmployeSeeder implements CommandLineRunner {

    private final EmployeRepository repository;

    public EmployeSeeder(EmployeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(List.of(
                new Employe("Sara", "Bennani", true),
                new Employe("Karim", "El Fassi", true),
                new Employe("Yasmine", "Alaoui", true),
                new Employe("Omar", "Tazi", false) // sans compte : n'apparaîtra pas comme approbateur
        ));
    }
}
