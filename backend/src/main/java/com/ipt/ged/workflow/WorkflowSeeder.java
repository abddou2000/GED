package com.ipt.ged.workflow;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Données de démonstration calquées sur la maquette de référence :
 * 24 règles (18 actives, 4 brouillons, 2 archivées), avec les 4 lignes nommées du design.
 * Ne s'exécute que sur base vide, et après le seeding des employés (@Order 1 -> 2).
 */
@Component
@Order(2)
@Profile("!test")
public class WorkflowSeeder implements CommandLineRunner {

    private final WorkflowRepository repo;
    private final EmployeRepository employes;

    public WorkflowSeeder(WorkflowRepository repo, EmployeRepository employes) {
        this.repo = repo;
        this.employes = employes;
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

        // --- Les 4 lignes nommées de la maquette ---
        seed("Validation contrat RH", "ACTIVE", "Ressources Humaines", "23/07/2026 10:24", false,
                List.of("Soumission", "Validation RH", "Signature"), emps);
        seed("Chronologie projet", "DRAFT", "Projets", "20/07/2026 09:15", false,
                List.of("Création", "Relecture", "Approbation"), emps);
        seed("Validation facture fournisseur", "ACTIVE", "Finance", "18/07/2026 16:40", false,
                List.of("Dépôt", "Contrôle", "Validation finance", "Archivage"), emps);
        seed("Demande d'achat", "ACTIVE", "Achats", "15/07/2026 14:05", false,
                List.of("Demande", "Validation", "Commande"), emps);

        String[] ws = {"Ressources Humaines", "Projets", "Finance", "Achats", "Direction", "Juridique", "Qualité"};
        String[] names = {"Validation note de frais", "Circuit congés", "Onboarding", "Validation devis",
                "Clôture mensuelle", "Revue contrat", "Bon de commande", "Validation budget", "Audit interne",
                "Publication document", "Mobilité interne", "Sortie de stock", "Validation dépense",
                "Circuit signature", "Contrôle qualité", "Revue projet"};

        // 15 actives supplémentaires (total actives = 18)
        for (int i = 0; i < 15; i++) {
            seed(names[i % names.length], "ACTIVE", ws[i % ws.length], date(i), false,
                    List.of("Étape 1", "Étape 2"), emps);
        }
        // 3 brouillons supplémentaires (total brouillons = 4)
        for (int i = 0; i < 3; i++) {
            seed("Brouillon " + names[i % names.length], "DRAFT", ws[i % ws.length], date(i + 15), false,
                    List.of("Rédaction", "Relecture"), emps);
        }
        // 2 archivées (corbeille) — dates anciennes -> apparaissent en dernières pages
        seed("Archivé " + names[0], "ACTIVE", ws[0], "12/05/2026 09:20", true, List.of("Étape"), emps);
        seed("Archivé " + names[1], "ACTIVE", ws[1], "03/05/2026 14:05", true, List.of("Étape"), emps);
    }

    private void seed(String name, String status, String workspace, String date, boolean deleted,
                      List<String> stepLabels, List<Employe> emps) {
        WorkflowGed w = new WorkflowGed(name);
        w.setStatus(status);
        w.setWorkspaceName(workspace);
        w.setLastModified(date);
        w.setDeleted(deleted);
        for (int i = 0; i < stepLabels.size(); i++) {
            w.addStep(new WorkflowStep(emps.get(i % emps.size()), stepLabels.get(i), i + 1));
        }
        repo.save(w);
    }

    private String date(int i) {
        int day = 1 + (i % 22);
        int hour = 8 + (i % 10);
        int min = (i * 7) % 60;
        return String.format("%02d/07/2026 %02d:%02d", day, hour, min);
    }
}
