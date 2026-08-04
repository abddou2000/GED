package com.ipt.ged.planindexation;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.planindexation.dto.PlanIndexationRequest;
import com.ipt.ged.planindexation.dto.PlanIndexationResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Logique métier des plans d'indexation : CRUD, corbeille et regroupement
 * ordonné des index (l'ordre reçu détermine l'ordre de nommage).
 */
@Service
public class PlanIndexationService {

    private final PlanIndexationRepository repo;
    private final IndexRepository indexRepo;

    public PlanIndexationService(PlanIndexationRepository repo, IndexRepository indexRepo) {
        this.repo = repo;
        this.indexRepo = indexRepo;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanIndexationResponse> list(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PlanIndexation> result = repo.findByDeletedFalseAndNomDuPlanContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, PlanIndexationResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanIndexationResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PlanIndexation> result = repo.findByDeletedTrueAndNomDuPlanContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, PlanIndexationResponse::from);
    }

    @Transactional(readOnly = true)
    public PlanIndexationResponse get(Long id) {
        return PlanIndexationResponse.from(load(id));
    }

    @Transactional
    public PlanIndexationResponse create(PlanIndexationRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        PlanIndexation p = new PlanIndexation(req.code(), req.nomDuPlan());
        apply(p, req);
        return PlanIndexationResponse.from(repo.save(p));
    }

    @Transactional
    public PlanIndexationResponse update(Long id, PlanIndexationRequest req) {
        PlanIndexation p = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        p.setCode(req.code());
        apply(p, req);
        return PlanIndexationResponse.from(repo.save(p));
    }

    @Transactional
    public void softDelete(Long id) {
        load(id).setDeleted(true);
    }

    @Transactional
    public void restore(Long id) {
        load(id).setDeleted(false);
    }

    @Transactional
    public void multipleDelete(List<Long> ids) {
        repo.findByIdInAndDeletedFalse(ids).forEach(p -> p.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(p -> p.setDeleted(false));
    }

    /** Liste allégée {id, name} pour les sélecteurs (types de document). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getNomDuPlan());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private PlanIndexation load(Long id) {
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
        List<Long> ids = req.indexIds() != null ? req.indexIds() : List.of();
        Map<Long, IndexField> byId = indexRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(IndexField::getId, Function.identity()));
        List<IndexField> ordered = new ArrayList<>();
        for (Long id : ids) {
            IndexField x = byId.get(id);
            if (x != null) ordered.add(x);
        }
        p.getIndices().clear();
        p.getIndices().addAll(ordered);
    }
}
