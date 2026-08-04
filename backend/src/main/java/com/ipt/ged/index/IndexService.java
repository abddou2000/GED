package com.ipt.ged.index;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.index.dto.IndexRequest;
import com.ipt.ged.index.dto.IndexResponse;
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
 * Logique métier des index (champs de métadonnées) : CRUD, corbeille et
 * normalisation (les valeurs ne sont conservées que pour le type LISTE).
 */
@Service
public class IndexService {

    private final IndexRepository repo;

    public IndexService(IndexRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public PageResponse<IndexResponse> list(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<IndexField> result = repo.findByDeletedFalseAndNomIndexContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, IndexResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<IndexResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<IndexField> result = repo.findByDeletedTrueAndNomIndexContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, IndexResponse::from);
    }

    @Transactional(readOnly = true)
    public IndexResponse get(Long id) {
        return IndexResponse.from(load(id));
    }

    @Transactional
    public IndexResponse create(IndexRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        IndexField x = new IndexField(req.code(), req.nomIndex());
        apply(x, req);
        return IndexResponse.from(repo.save(x));
    }

    @Transactional
    public IndexResponse update(Long id, IndexRequest req) {
        IndexField x = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        x.setCode(req.code());
        apply(x, req);
        return IndexResponse.from(repo.save(x));
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
        repo.findByIdInAndDeletedFalse(ids).forEach(x -> x.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(x -> x.setDeleted(false));
    }

    /** Liste allégée {id, name} pour les sélecteurs (plans d'indexation). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", x.getId());
                    m.put("name", x.getNomIndex());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private IndexField load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Index introuvable : " + id));
    }

    private void apply(IndexField x, IndexRequest req) {
        x.setNomIndex(req.nomIndex());
        x.setFieldType(req.fieldType());
        // Les valeurs n'ont de sens que pour le type LISTE.
        x.setValeurs(req.fieldType() == IndexFieldType.LISTE ? blankToNull(req.valeurs()) : null);
        x.setValeurParDefaut(blankToNull(req.valeurParDefaut()));
        x.setObligatoire(req.obligatoire());
        x.setIndexePourRecherche(req.indexePourRecherche());
        x.setIndexDeGroupage(req.indexDeGroupage());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
