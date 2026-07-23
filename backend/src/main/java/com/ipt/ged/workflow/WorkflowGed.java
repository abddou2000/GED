package com.ipt.ged.workflow;

import com.ipt.ged.common.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Circuit de validation (« Règle de Workflow ») : un nom + une liste d'étapes ordonnées.
 */
@Entity
@Table(name = "workflow_ged")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowGed extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Statut métier affiché en pastille : ACTIVE | DRAFT. */
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** Espace de travail rattaché (nom affiché dans la liste). */
    @Column
    private String workspaceName;

    /** Date de dernière modification, pré-formatée pour l'affichage (ex. « 23/07/2026 10:24 »). */
    @Column
    private String lastModified;

    /** Corbeille : true = archivé/supprimé de façon réversible (onglet « Archivées »). */
    @Column(nullable = false)
    private boolean deleted = false;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<WorkflowStep> steps = new ArrayList<>();

    public WorkflowGed(String name) {
        this.name = name;
    }

    /** Ajoute une étape en maintenant le lien bidirectionnel. */
    public void addStep(WorkflowStep step) {
        step.setWorkflow(this);
        this.steps.add(step);
    }

    /** Vide les étapes (orphanRemoval les supprimera en base) — utilisé lors de l'édition. */
    public void clearSteps() {
        this.steps.clear();
    }
}
