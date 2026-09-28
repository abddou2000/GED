package com.ipt.ged.planindexation.metamodele;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.typedocument.TypeDocument;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versions des plans d'indexation (§12.7, gouvernance) : « toute
 * modification d'un type produit une nouvelle version du plan sans altérer
 * les documents déjà déposés, qui référencent la version en vigueur à leur
 * dépôt ».
 *
 * <p>{@link #enVigueur(PlanIndexation)} compare la définition courante du plan
 * (ses index, leur nature, leur caractère obligatoire, leurs valeurs) à sa
 * dernière version, et n'en crée une nouvelle que si elle diffère. Appelé à
 * chaque modification d'un plan, d'un index ou du plan d'un type, et à chaque
 * dépôt : aucune version n'est jamais manquée, aucune n'est créée pour rien.
 */
@Service
public class ServiceVersionsPlan {

    private static final TypeReference<Map<String, Object>> CARTE = new TypeReference<>() {};

    private final PlanIndexationVersionRepository versions;
    private final PlanIndexationRepository plans;
    private final ObjectMapper json;

    public ServiceVersionsPlan(PlanIndexationVersionRepository versions, PlanIndexationRepository plans,
                               ObjectMapper json) {
        this.versions = versions;
        this.plans = plans;
        this.json = json;
    }

    /** Version en vigueur du plan d'un type ; vide si le type n'a pas de plan. */
    @Transactional
    public Optional<PlanIndexationVersion> enVigueur(TypeDocument type) {
        if (type == null || type.getPlanIndexation() == null) return Optional.empty();
        return Optional.of(enVigueur(type.getPlanIndexation()));
    }

    /** Version en vigueur d'un plan, créée si sa définition a changé depuis la dernière. */
    @Transactional
    public PlanIndexationVersion enVigueur(PlanIndexation plan) {
        DefinitionPlan courante = DefinitionPlan.depuis(plan);
        Optional<PlanIndexationVersion> derniere = versions.findFirstByPlanIndexationIdOrderByNumeroDesc(plan.getId());
        if (derniere.isPresent() && definition(derniere.get()).equals(courante)) {
            return derniere.get();
        }
        int numero = derniere.map(v -> v.getNumero() + 1).orElse(1);
        return versions.saveAndFlush(new PlanIndexationVersion(plan.getId(), numero,
                json.convertValue(courante, CARTE), ActeurCourant.utilisateurId()));
    }

    /** Versionne tous les plans qui contiennent un index (modification d'un index). */
    @Transactional
    public void versionnerPlansDeLIndex(UUID indexId) {
        plans.findAll().stream()
                .filter(p -> p.getIndices().stream().anyMatch(i -> i.getId().equals(indexId)))
                .forEach(this::enVigueur);
    }

    /** Numéro de la dernière version d'un plan, sans en créer ; {@code null} s'il n'en a aucune. */
    @Transactional(readOnly = true)
    public Integer derniereVersion(UUID planId) {
        return versions.findFirstByPlanIndexationIdOrderByNumeroDesc(planId).map(PlanIndexationVersion::getNumero)
                .orElse(null);
    }

    /** Définition d'une version. */
    public DefinitionPlan definition(PlanIndexationVersion v) {
        return json.convertValue(v.getDefinition(), DefinitionPlan.class);
    }

    @Transactional(readOnly = true)
    public DefinitionPlan definition(UUID versionId) {
        return definition(versions.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version de plan introuvable : " + versionId)));
    }
}
