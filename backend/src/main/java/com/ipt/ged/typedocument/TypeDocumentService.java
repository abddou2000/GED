package com.ipt.ged.typedocument;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.typedocument.dto.TypeDocumentRequest;
import com.ipt.ged.typedocument.dto.TypeDocumentResponse;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logique métier des types de document : CRUD, corbeille, rattachement à un
 * espace de travail (obligatoire) et à un plan d'indexation (facultatif).
 */
@Service
public class TypeDocumentService {

    private final TypeDocumentRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    private final PlanIndexationRepository planRepo;

    public TypeDocumentService(TypeDocumentRepository repo, WorkSpaceRepository workspaceRepo,
                               PlanIndexationRepository planRepo) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.planRepo = planRepo;
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> list(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<TypeDocument> result = repo.findByDeletedFalseAndTypeDeDocumentContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<TypeDocument> result = repo.findByDeletedTrueAndTypeDeDocumentContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public TypeDocumentResponse get(Long id) {
        return TypeDocumentResponse.from(load(id));
    }

    @Transactional
    public TypeDocumentResponse create(TypeDocumentRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        TypeDocument t = new TypeDocument(req.code(), req.typeDeDocument());
        apply(t, req);
        return TypeDocumentResponse.from(repo.save(t));
    }

    @Transactional
    public TypeDocumentResponse update(Long id, TypeDocumentRequest req) {
        TypeDocument t = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        t.setCode(req.code());
        t.setTypeDeDocument(req.typeDeDocument());
        apply(t, req);
        return TypeDocumentResponse.from(repo.save(t));
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
        repo.findByIdInAndDeletedFalse(ids).forEach(t -> t.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(t -> t.setDeleted(false));
    }

    /** Liste allégée {id, name} pour les sélecteurs (upload). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getTypeDeDocument());
                    // Nom du plan d'indexation, ou null : le dépôt s'en sert pour
                    // prévenir qu'un type sans plan ne demandera aucun index.
                    m.put("plan", t.getPlanIndexation() != null ? t.getPlanIndexation().getNomDuPlan() : null);
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private TypeDocument load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + id));
    }

    private void apply(TypeDocument t, TypeDocumentRequest req) {
        t.setDescription(req.description());

        WorkSpace ws = workspaceRepo.findById(req.workspaceId())
                .orElseThrow(() -> new EntityNotFoundException("Espace de travail introuvable : " + req.workspaceId()));
        t.setWorkspace(ws);

        if (req.planIndexationId() != null) {
            PlanIndexation plan = planRepo.findById(req.planIndexationId())
                    .orElseThrow(() -> new EntityNotFoundException("Plan d'indexation introuvable : " + req.planIndexationId()));
            t.setPlanIndexation(plan);
        } else {
            t.setPlanIndexation(null);
        }

        t.setTypeAutorise(req.typeAutorise() != null ? String.join(",", req.typeAutorise()) : null);
        t.setTailleMaxMo(req.tailleMaxMo());
    }
}
