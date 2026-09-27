package com.ipt.ged.workflow;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Règle de workflow (§12.8, table {@code regle_workflow}) : un nom et ses
 * validateurs, SANS ORDRE (D7 : tous sollicités en même temps). Rattachée à un
 * type de document ou à un nœud ; modifiable à tout moment, avec effet sur les
 * seuls dépôts futurs (le circuit d'un document est une copie figée).
 * La classe garde son nom historique ; les « étapes » sont les validateurs.
 */
@Entity
@Table(name = "regle_workflow")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowGed extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(nullable = false)
    private String name;


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
