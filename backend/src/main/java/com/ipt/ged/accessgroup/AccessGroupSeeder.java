package com.ipt.ged.accessgroup;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration : deux groupes d'accès rattachant des utilisateurs à des
 * espaces. S'exécute après les espaces de travail, sur base vide, hors tests.
 */
@Component
@Order(4)
// Jeu de DÉMONSTRATION, profil dev uniquement : ce sont des référentiels
// métier, qui ne se créent en production que depuis l'interface (dossier
// technique §4.2.1). Ni changeset ni amorçage automatique en prod.
@Profile("dev")
public class AccessGroupSeeder implements CommandLineRunner {

    private final AccessGroupRepository repo;
    private final WorkSpaceRepository workspaces;
    private final EmployeRepository employes;

    public AccessGroupSeeder(AccessGroupRepository repo, WorkSpaceRepository workspaces,
                             EmployeRepository employes) {
        this.repo = repo;
        this.workspaces = workspaces;
        this.employes = employes;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        List<WorkSpace> allWs = workspaces.findBySupprimeFalseOrderByIdAsc();
        List<Employe> users = employes.findByHasUserTrue();
        if (allWs.isEmpty() || users.isEmpty()) {
            return;
        }

        // Administrateurs GED : tous les espaces
        AccessGroup admin = new AccessGroup("AG-ADMIN", "Administrateurs GED");
        admin.getWorkspaces().addAll(allWs);
        admin.getUsers().add(users.get(0));
        repo.save(admin);

        // Lecteurs Comptabilité : premier espace
        AccessGroup lecteurs = new AccessGroup("AG-LECT", "Lecteurs Comptabilité");
        lecteurs.getWorkspaces().add(allWs.get(0));
        lecteurs.getUsers().add(users.get(users.size() > 1 ? 1 : 0));
        repo.save(lecteurs);
    }
}
