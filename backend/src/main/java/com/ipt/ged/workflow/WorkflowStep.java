package com.ipt.ged.workflow;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Auditable;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Validateur d'une règle (table {@code regle_validateur}) : NOMMÉ (employé)
 * ou désigné par RÔLE sur un périmètre (nœud ; à défaut, l'emplacement du
 * document), résolu au moment de la décision. Le rang n'est qu'un ordre
 * d'affichage : aucun ordre n'est imposé (D7).
 */
@Entity
@Table(name = "regle_validateur")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowStep extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "regle_workflow_id", nullable = false)
    private WorkflowGed workflow;

    /** Validateur nommé ; {@code null} pour un validateur par rôle. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employe_id")
    private Employe employe;

    /** Rôle du validateur désigné par rôle. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private com.ipt.ged.identite.Role role;

    /** Périmètre du rôle ; {@code null} = l'emplacement principal du document. */
    @Column(name = "perimetre_noeud_id")
    private UUID perimetreNoeudId;

    @Column(nullable = false)
    private String label;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    public WorkflowStep(Employe employe, String label, int stepOrder) {
        this.employe = employe;
        this.label = label;
        this.stepOrder = stepOrder;
    }
}
