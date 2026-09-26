package com.ipt.ged.planindexation;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration : un plan « Fiche Facture » regroupant quelques index.
 * S'exécute après les index, sur base vide, hors profil de test.
 */
@Component
@Order(6)
// Jeu de DÉMONSTRATION, profil dev uniquement : ce sont des référentiels
// métier, qui ne se créent en production que depuis l'interface (dossier
// technique §4.2.1). Ni changeset ni amorçage automatique en prod.
@Profile("dev")
public class PlanIndexationSeeder implements CommandLineRunner {

    private final PlanIndexationRepository repo;
    private final IndexRepository indexRepo;

    public PlanIndexationSeeder(PlanIndexationRepository repo, IndexRepository indexRepo) {
        this.repo = repo;
        this.indexRepo = indexRepo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        List<IndexField> indices = indexRepo.findByDeletedFalseOrderByIdAsc();
        if (indices.isEmpty()) {
            return;
        }
        PlanIndexation plan = new PlanIndexation("PLAN-FACT", "Fiche Facture");
        plan.setModeIndexation(true);
        // « _ » et non « - » : le plan contient une date ISO (2026-01-15), dont les
        // tirets casseraient le découpage du nom de fichier à l'indexation automatique.
        plan.setSeparateur("_");
        plan.setMajuscule(false);
        // Numéro de facture, Date d'émission, Fournisseur, Priorité — un index de
        // chaque type, pour que le formulaire d'indexation les illustre tous.
        plan.getIndices().addAll(indices.stream().limit(4).toList());
        repo.save(plan);
    }
}
