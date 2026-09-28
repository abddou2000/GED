package com.ipt.ged.workflow.circuit;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.document.UploadDocument;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Circuit de validation d'un document (§12.8) : copie FIGÉE, au dépôt, de la
 * règle applicable. Ses validateurs sont sollicités en même temps (D7) ; son
 * statut est recalculé à chaque décision ou versement.
 */
@Entity
@Table(name = "circuit")
@Getter
@Setter
@NoArgsConstructor
public class Circuit {

    public enum Statut { EN_COURS, VALIDE, REFUSE, ANNULE }

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private UploadDocument document;

    /** Règle d'origine (information : le circuit ne la relit jamais). */
    @Column(name = "regle_workflow_id")
    private UUID regleWorkflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private Statut statut = Statut.EN_COURS;

    /** Identité GED de l'initiateur (le déposant, ou qui a rouvert le circuit). */
    @Column(name = "initiateur_id")
    private UUID initiateurId;

    @Column(name = "ouvert_le", nullable = false)
    private Instant ouvertLe = Instant.now();

    @Column(name = "clos_le")
    private Instant closLe;

    @Column(name = "annule_par")
    private UUID annulePar;

    @Column(name = "annule_le")
    private Instant annuleLe;

    @Column(name = "motif_annulation", length = 500)
    private String motifAnnulation;

    @OneToMany(mappedBy = "circuit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<CircuitValidateur> validateurs = new ArrayList<>();

    public Circuit(UploadDocument document, UUID regleWorkflowId, UUID initiateurId) {
        this.document = document;
        this.regleWorkflowId = regleWorkflowId;
        this.initiateurId = initiateurId;
    }

    public void ajouter(CircuitValidateur v) {
        v.setCircuit(this);
        validateurs.add(v);
    }
}
