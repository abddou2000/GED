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
 * Données de démonstration : deux groupes d'accès (un administrateur tous droits,
 * un lecteur). S'exécute après les espaces de travail, sur base vide, hors tests.
 */
@Component
@Order(4)
@Profile("!test")
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
        List<WorkSpace> allWs = workspaces.findByDeletedFalseOrderByIdAsc();
        List<Employe> users = employes.findByHasUserTrue();
        if (allWs.isEmpty() || users.isEmpty()) {
            return;
        }

        // Administrateurs GED : tous les droits, tous les espaces
        AccessGroup admin = new AccessGroup("AG-ADMIN", "Administrateurs GED");
        GedRights full = admin.getRights();
        full.setSupprimer(true);
        full.setDeplacer(true);
        full.setAjouterVersion(true);
        full.setVerrouillerDeverrouiller(true);
        full.normalize();
        admin.getWorkspaces().addAll(allWs);
        admin.getUsers().add(users.get(0));
        repo.save(admin);

        // Lecteurs Comptabilité : lecture seule, premier espace
        AccessGroup lecteurs = new AccessGroup("AG-LECT", "Lecteurs Comptabilité");
        GedRights read = lecteurs.getRights();
        read.setLecture(true);
        read.normalize();
        lecteurs.getWorkspaces().add(allWs.get(0));
        lecteurs.getUsers().add(users.get(users.size() > 1 ? 1 : 0));
        repo.save(lecteurs);
    }
}
