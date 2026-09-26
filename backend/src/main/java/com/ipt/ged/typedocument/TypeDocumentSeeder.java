package com.ipt.ged.typedocument;

import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration : quelques types de document rattachés aux dossiers.
 * S'exécute après les espaces de travail et les plans, sur base vide, hors tests.
 */
@Component
@Order(7)
// Jeu de DÉMONSTRATION, profil dev uniquement : ce sont des référentiels
// métier, qui ne se créent en production que depuis l'interface (dossier
// technique §4.2.1). Ni changeset ni amorçage automatique en prod.
@Profile("dev")
public class TypeDocumentSeeder implements CommandLineRunner {

    private final TypeDocumentRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    private final PlanIndexationRepository planRepo;

    public TypeDocumentSeeder(TypeDocumentRepository repo, WorkSpaceRepository workspaceRepo,
                              PlanIndexationRepository planRepo) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.planRepo = planRepo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        List<WorkSpace> ws = workspaceRepo.findByDeletedFalseOrderByIdAsc();
        if (ws.isEmpty()) {
            return;
        }
        PlanIndexation plan = planRepo.findByDeletedFalseOrderByIdAsc().stream().findFirst().orElse(null);

        TypeDocument facture = new TypeDocument("TD-FACT", "Facture");
        facture.setDescription("Factures fournisseurs et clients");
        facture.setWorkspace(ws.get(0));
        facture.setPlanIndexation(plan);
        facture.setTypeAutorise("pdf,docx");
        facture.setTailleMaxMo(10);
        repo.save(facture);

        TypeDocument contrat = new TypeDocument("TD-CONTRAT", "Contrat");
        contrat.setDescription("Contrats et avenants");
        contrat.setWorkspace(ws.get(ws.size() > 1 ? 1 : 0));
        contrat.setTypeAutorise("pdf");
        contrat.setTailleMaxMo(20);
        repo.save(contrat);
    }
}
