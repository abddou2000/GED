package com.ipt.ged.planindexation;

import com.ipt.ged.common.Auditable;
import com.ipt.ged.index.IndexField;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan d'indexation : regroupe des index (champs de métadonnées) en une « fiche »,
 * rattachée ensuite à un type de document. Porte une charte de nommage
 * (séparateur + casse) appliquée aux index, dans l'ordre. Reproduit
 * {@code Plan_d_indexation} de CCISTTA.
 */
@Entity
@Table(name = "plan_d_indexations")
@Getter
@Setter
@NoArgsConstructor
public class PlanIndexation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "nom_du_plan", nullable = false)
    private String nomDuPlan;

    /** Mode d'indexation : true = automatique, false = manuel. */
    @Column(name = "mode_indexation", nullable = false)
    private boolean modeIndexation = false;

    /** Charte de nommage : true = saisie manuelle, false = composée automatiquement. */
    @Column(nullable = false)
    private boolean manuel = false;

    @Column(nullable = false)
    private boolean majuscule = false;

    @Column(nullable = false)
    private String separateur = "_";

    /** Index regroupés par le plan, dans l'ordre (sert au nommage). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "pivot_plan_d_indexation_indices",
            joinColumns = @JoinColumn(name = "plan_d_indexation_id"),
            inverseJoinColumns = @JoinColumn(name = "index_id"))
    @OrderColumn(name = "position")
    private List<IndexField> indices = new ArrayList<>();

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public PlanIndexation(String code, String nomDuPlan) {
        this.code = code;
        this.nomDuPlan = nomDuPlan;
    }
}
