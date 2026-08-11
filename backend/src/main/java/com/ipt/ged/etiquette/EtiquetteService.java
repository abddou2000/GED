package com.ipt.ged.etiquette;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.etiquette.dto.EtiquetteRequest;
import com.ipt.ged.etiquette.dto.EtiquetteResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Logique métier des étiquettes : CRUD simple + corbeille.
 */
@Service
public class EtiquetteService {

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */

    private static final Set<String> TRIS = Set.of("id", "code", "tag");


    private final EtiquetteRepository repo;

    public EtiquetteService(EtiquetteRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public PageResponse<EtiquetteResponse> list(int page, int size, String search,
                                                String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<Etiquette> result = repo.findByDeletedFalseAndTagContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, EtiquetteResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<EtiquetteResponse> trashed(int page, int size, String search,
                                                   String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<Etiquette> result = repo.findByDeletedTrueAndTagContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, EtiquetteResponse::from);
    }

    @Transactional(readOnly = true)
    public EtiquetteResponse get(Long id) {
        return EtiquetteResponse.from(load(id));
    }

    @Transactional
    public EtiquetteResponse create(EtiquetteRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        Etiquette e = new Etiquette(req.code(), req.tag(), req.couleur());
        return EtiquetteResponse.from(repo.save(e));
    }

    @Transactional
    public EtiquetteResponse update(Long id, EtiquetteRequest req) {
        Etiquette e = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        e.setCode(req.code());
        e.setTag(req.tag());
        e.setCouleur(req.couleur());
        return EtiquetteResponse.from(repo.save(e));
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
        repo.findByIdInAndDeletedFalse(ids).forEach(e -> e.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(e -> e.setDeleted(false));
    }

    /** Liste allégée {id, name} pour les sélecteurs (upload). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("name", e.getTag());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private Etiquette load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Étiquette introuvable : " + id));
    }
}
