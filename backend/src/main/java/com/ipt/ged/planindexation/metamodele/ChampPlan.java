package com.ipt.ged.planindexation.metamodele;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;

import java.util.Arrays;
import java.util.List;

/**
 * Un index tel qu'il figure dans une VERSION de plan (méta-modèle §12.7) :
 * copie figée de {@code index_def} au moment de la version. Les noms de
 * composantes sont ceux de la définition JSONB de {@code plan_indexation_version}
 * (changeset 202609301040) — ne pas les renommer.
 *
 * @param valeurs valeurs de la liste, telles que stockées (séparées par des virgules)
 */
public record ChampPlan(String id, String code, String libelle, String nature, boolean obligatoire,
                        String valeurs, String valeurDefaut, boolean indexeRecherche) {

    public static ChampPlan depuis(IndexField i) {
        return new ChampPlan(i.getId().toString(), i.getCode(), i.getNomIndex(), i.getFieldType().name(),
                i.isObligatoire(), i.getValeurs(), i.getValeurParDefaut(), i.isIndexePourRecherche());
    }

    public IndexFieldType natureTypee() {
        return IndexFieldType.valueOf(nature);
    }

    /** Valeurs autorisées d'un index de nature liste. */
    public List<String> options() {
        if (valeurs == null || valeurs.isBlank()) return List.of();
        return Arrays.stream(valeurs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
