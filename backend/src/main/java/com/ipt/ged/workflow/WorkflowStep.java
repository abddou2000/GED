package com.ipt.ged.workflow;

import com.ipt.ged.common.IdentifiantUuid;
import java.util.UUID;
import com.ipt.ged.common.Auditable;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une étape d'un circuit : un approbateur (employé) + un libellé + un rang d'ordre.
 */
@Entity
@Table(name = "workflow_ged_etape")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowStep extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_ged_id", nullable = false)
    private WorkflowGed workflow;

    /** L'approbateur assigné à cette étape. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employe_id", nullable = false)
    private Employe employe;

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
