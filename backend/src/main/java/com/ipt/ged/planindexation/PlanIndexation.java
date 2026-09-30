package com.ipt.ged.planindexation;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import com.ipt.ged.index.IndexField;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plan d'indexation : regroupe des index (champs de métadonnées) en une « fiche »,
 * rattachée ensuite à un type de document. Porte une charte de nommage
 * (séparateur + casse) appliquée aux index, dans l'ordre. Reproduit
 * {@code Plan_d_indexation} de CCISTTA.
 */
@Entity
@Table(name = "plan_indexation")
@Getter
@Setter
@NoArgsConstructor
public class PlanIndexation extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

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
    @JoinTable(name = "plan_index",
            joinColumns = @JoinColumn(name = "plan_indexation_id"),
            inverseJoinColumns = @JoinColumn(name = "index_def_id"))
    @OrderColumn(name = "position")
    private List<IndexField> indices = new ArrayList<>();

    /**
     * Charte de nommage sérialisée : {@code {"indexs":[...],"separateur":"_","majuscule":false}}.
     *
     * <p>Distincte de {@link #indices} : le plan regroupe des champs de
     * métadonnées, la charte décide lesquels composent le nom du fichier, dans
     * quel ordre, et peut y mêler des jetons système (DATE, ANNÉE…) qui
     * n'existent dans aucune table. Réutiliser {@code indices} interdirait ces
     * deux libertés. {@code null} en nommage manuel.
     */
    @Column(name = "charte_nommage", length = 2000)
    private String charteNommage;


    public PlanIndexation(String code, String nomDuPlan) {
        this.code = code;
        this.nomDuPlan = nomDuPlan;
    }
}
