package com.ipt.ged.workflow.circuit;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Décision d'un validateur sur une VERSION du document (§12.8) : nominative
 * (auteur), horodatée, motivée (motif obligatoire au refus). Jamais modifiée :
 * changer d'avis, c'est une décision de plus (ANNULEE, puis une nouvelle).
 */
@Entity
@Table(name = "decision")
@Getter
@NoArgsConstructor
public class Decision {

    public enum Type { VALIDE, REFUSE, ANNULEE }

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circuit_validateur_id", nullable = false, updatable = false)
    private CircuitValidateur validateur;

    /** Version décidée ; {@code null} pour un document repris sans version. */
    @Column(name = "version_id", updatable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 10, updatable = false)
    private Type decision;

    @Column(length = 500, updatable = false)
    private String motif;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    /** Identité GED de la personne qui décide (le délégué pour une application). */
    @Column(name = "auteur_id", updatable = false)
    private UUID auteurId;

    /** Application appelante (clé d'API), s'il y en a une : double identité dans l'audit. */
    @Column(name = "application_id", updatable = false)
    private UUID applicationId;

    public Decision(CircuitValidateur validateur, UUID versionId, Type decision, String motif, UUID auteurId,
                    UUID applicationId) {
        this.validateur = validateur;
        this.versionId = versionId;
        this.decision = decision;
        this.motif = motif;
        this.auteurId = auteurId;
        this.applicationId = applicationId;
    }
}
