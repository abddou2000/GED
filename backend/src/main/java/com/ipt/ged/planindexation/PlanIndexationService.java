package com.ipt.ged.planindexation;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.planindexation.dto.PlanIndexationRequest;
import com.ipt.ged.planindexation.dto.PlanIndexationResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Logique métier des plans d'indexation : CRUD, corbeille et regroupement
 * ordonné des index (l'ordre reçu détermine l'ordre de nommage).
 */
@Service
public class PlanIndexationService {

    /** Journal d'audit des opérations d'administration (DAT §7.4.1). */
    private final JournalAdministration journal;

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS = Set.of("id", "code", "nomDuPlan");

    private final PlanIndexationRepository repo;
    private final IndexRepository indexRepo;

    /** Versions figées du plan (§12.7) : chaque modification en produit une. */
    private final com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versions;

    public PlanIndexationService(PlanIndexationRepository repo, IndexRepository indexRepo,
                                 JournalAdministration journal,
                                 com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versions) {
        this.journal = journal;
        this.repo = repo;
        this.indexRepo = indexRepo;
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanIndexationResponse> list(int page, int size, String search,
                                                     String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<PlanIndexation> result = repo.findBySupprimeFalseAndNomDuPlanContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, PlanIndexationResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanIndexationResponse> trashed(int page, int size, String search,
                                                        String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<PlanIndexation> result = repo.findBySupprimeTrueAndNomDuPlanContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, PlanIndexationResponse::from);
    }

    @Transactional(readOnly = true)
    public PlanIndexationResponse get(UUID id) {
        return PlanIndexationResponse.from(load(id));
    }

    // Libellé volontairement recopié tel quel du catalogue : « create
    // plan_d_indexation » (technique) diffère de « update Plan d'indexation »
    // (littéraire). Toute normalisation ici casserait la règle.
    @Transactional
    public PlanIndexationResponse create(PlanIndexationRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        PlanIndexation p = new PlanIndexation(req.code(), req.nomDuPlan());
        apply(p, req);
        PlanIndexationResponse cree = PlanIndexationResponse.from(repo.save(p));
        journal.cree(ActionAudit.PLAN_INDEXATION_CREE, "PLAN_INDEXATION", cree.id(), cree);
        return cree;
    }

    @Transactional
    public PlanIndexationResponse update(UUID id, PlanIndexationRequest req) {
        PlanIndexation p = load(id);
        PlanIndexationResponse avant = PlanIndexationResponse.from(p);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        p.setCode(req.code());
        apply(p, req);
        PlanIndexation enregistre = repo.saveAndFlush(p);
        // Nouvelle version si les index du plan ont changé : les documents déjà
        // déposés gardent la leur (§12.7).
        versions.enVigueur(enregistre);
        PlanIndexationResponse apres = PlanIndexationResponse.from(enregistre);
        journal.modifie(ActionAudit.PLAN_INDEXATION_MODIFIE, "PLAN_INDEXATION", id, avant, apres);
        return apres;
    }

    @Transactional
    public void softDelete(UUID id) {
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
        journal.action(ActionAudit.PLAN_INDEXATION_SUPPRIME, "PLAN_INDEXATION", id);
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
        journal.action(ActionAudit.PLAN_INDEXATION_RESTAURE, "PLAN_INDEXATION", id);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndSupprimeFalse(ids).forEach(p -> {
            p.mettreEnCorbeille(ActeurCourant.employeId());
            journal.action(ActionAudit.PLAN_INDEXATION_SUPPRIME, "PLAN_INDEXATION", p.getId());
        });
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndSupprimeTrue(ids).forEach(p -> {
            p.restaurer();
            journal.action(ActionAudit.PLAN_INDEXATION_RESTAURE, "PLAN_INDEXATION", p.getId());
        });
    }

    /** Liste allégée {id, name} pour les sélecteurs (types de document). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getNomDuPlan());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private PlanIndexation load(UUID id) {
        return repo.findWithIndicesById(id)
                .orElseThrow(() -> new EntityNotFoundException("Plan d'indexation introuvable : " + id));
    }

    private void apply(PlanIndexation p, PlanIndexationRequest req) {
        p.setNomDuPlan(req.nomDuPlan());
        p.setModeIndexation(req.modeIndexation());
        p.setManuel(req.manuel());
        p.setMajuscule(req.majuscule());
        p.setSeparateur(req.separateur() != null && !req.separateur().isBlank() ? req.separateur() : "_");

        // Index dans l'ordre reçu
        List<UUID> ids = req.indexIds() != null ? req.indexIds() : List.of();
        Map<UUID, IndexField> byId = indexRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(IndexField::getId, Function.identity()));
        List<IndexField> ordered = new ArrayList<>();
        for (UUID id : ids) {
            IndexField x = byId.get(id);
            if (x != null) ordered.add(x);
        }
        p.getIndices().clear();
        p.getIndices().addAll(ordered);

        // Charte de nommage : effacée en mode manuel, comme dans l'original —
        // conserver une charte inopérante laisserait croire qu'elle s'applique.
        p.setCharteNommage(req.manuel()
                ? null
                : CharteNommage.serialiser(req.charteIds(), p.getSeparateur(), p.isMajuscule()));
    }
}
