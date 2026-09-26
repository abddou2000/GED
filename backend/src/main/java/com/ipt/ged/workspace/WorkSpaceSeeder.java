package com.ipt.ged.workspace;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration : quelques dossiers hiérarchisés.
 * S'exécute après les employés, sur base vide, et hors profil de test.
 */
@Component
@Order(3)
// Jeu de DÉMONSTRATION, profil dev uniquement : ce sont des référentiels
// métier, qui ne se créent en production que depuis l'interface (dossier
// technique §4.2.1). Ni changeset ni amorçage automatique en prod.
@Profile("dev")
public class WorkSpaceSeeder implements CommandLineRunner {

    private final WorkSpaceRepository repo;
    private final EmployeRepository employes;
    private final WorkflowRepository workflows;

    public WorkSpaceSeeder(WorkSpaceRepository repo, EmployeRepository employes, WorkflowRepository workflows) {
        this.repo = repo;
        this.employes = employes;
        this.workflows = workflows;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        List<Employe> emps = employes.findByHasUserTrue();
        if (emps.isEmpty()) {
            return;
        }
        WorkflowGed wf = ensureWorkflow(emps);

        WorkSpace compta = create("Comptabilité", "WS-COMPTA", null, emps.get(0), wf);
        WorkSpace annee = create("2026", "WS-2026", compta, emps.get(0), wf);
        create("Factures", "WS-FACT", annee, emps.get(1), wf);

        WorkSpace rh = create("Ressources Humaines", "WS-RH", null, emps.get(1), wf);
        create("Contrats", "WS-CONTRATS", rh, emps.get(2), wf);

        create("Projets", "WS-PROJETS", null, emps.get(2), wf);
    }

    private WorkflowGed ensureWorkflow(List<Employe> emps) {
        if (workflows.count() > 0) {
            return workflows.findAll().get(0);
        }
        WorkflowGed wf = new WorkflowGed("Validation standard");
        wf.addStep(new WorkflowStep(emps.get(0), "Validation", 1));
        return workflows.save(wf);
    }

    private WorkSpace create(String name, String code, WorkSpace parent, Employe owner, WorkflowGed wf) {
        WorkSpace w = new WorkSpace(name, code);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(owner);
        w.setWorkflow(wf);
        w.setParent(parent);
        return repo.save(w);
    }
}
