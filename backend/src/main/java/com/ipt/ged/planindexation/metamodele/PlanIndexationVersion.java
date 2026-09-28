package com.ipt.ged.planindexation.metamodele;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Version figée d'un plan d'indexation (§12.7, gouvernance). Écrite une fois,
 * jamais modifiée : un document référence la version en vigueur à son dépôt.
 */
@Entity
@Table(name = "plan_indexation_version")
@Getter
@NoArgsConstructor
public class PlanIndexationVersion {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "plan_indexation_id", nullable = false, updatable = false)
    private UUID planIndexationId;

    @Column(nullable = false, updatable = false)
    private int numero;

    /** Définition : {@code {"champs": [ChampPlan…]}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, updatable = false)
    private Map<String, Object> definition;

    @Column(name = "cree_par", updatable = false)
    private UUID creePar;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe = Instant.now();

    public PlanIndexationVersion(UUID planIndexationId, int numero, Map<String, Object> definition, UUID creePar) {
        this.planIndexationId = planIndexationId;
        this.numero = numero;
        this.definition = definition;
        this.creePar = creePar;
    }
}
