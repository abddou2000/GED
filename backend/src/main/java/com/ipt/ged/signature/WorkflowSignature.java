package com.ipt.ged.signature;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.employe.Employe;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Une demande de signature dans le circuit de validation d'un document.
 * Créée à l'upload, une par étape du workflow du dossier. Le libellé et le rang
 * de l'étape sont copiés (instantané) pour rester stables si la règle évolue.
 */
@Entity
@Table(name = "workflow_ged_signatures")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowSignature extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    /** L'approbateur assigné à cette étape. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employe_id", nullable = false)
    private Employe employe;

    @Column(name = "step_label")
    private String stepLabel;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignatureStatus status = SignatureStatus.PENDING;

    @Column(name = "signed_at")
    private Instant signedAt;

    private String motif;
}
