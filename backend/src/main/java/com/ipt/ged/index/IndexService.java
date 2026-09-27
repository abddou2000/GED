package com.ipt.ged.index;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.index.dto.IndexRequest;
import com.ipt.ged.index.dto.IndexResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Logique métier des index (champs de métadonnées) : CRUD, corbeille et
 * normalisation (les valeurs ne sont conservées que pour le type LISTE).
 */
@Service
public class IndexService {

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS = Set.of(
            "id", "code", "nomIndex", "fieldType",
            "obligatoire", "indexePourRecherche", "indexDeGroupage");

    /** Colonnes booléennes : triées telles quelles (PostgreSQL refuse lower(boolean)). */
    private static final Set<String> TRIS_NON_TEXTE = Set.of(
            "obligatoire", "indexePourRecherche", "indexDeGroupage");

    private final IndexRepository repo;

    /** Versions figées des plans qui contiennent l'index (§12.7). */
    private final com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versions;

    public IndexService(IndexRepository repo, com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versions) {
        this.repo = repo;
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public PageResponse<IndexResponse> list(int page, int size, String search,
                                            String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NON_TEXTE);
        Page<IndexField> result = repo.findBySupprimeFalseAndNomIndexContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, IndexResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<IndexResponse> trashed(int page, int size, String search,
                                               String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NON_TEXTE);
        Page<IndexField> result = repo.findBySupprimeTrueAndNomIndexContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, IndexResponse::from);
    }

    @Transactional(readOnly = true)
    public IndexResponse get(UUID id) {
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
    public IndexResponse update(UUID id, IndexRequest req) {
        IndexField x = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        x.setCode(req.code());
        apply(x, req);
        IndexField enregistre = repo.saveAndFlush(x);
        // Modifier un index (nature, obligatoire, valeurs) modifie les plans qui
        // l'emploient : chacun reçoit une nouvelle version.
        versions.versionnerPlansDeLIndex(enregistre.getId());
        return IndexResponse.from(enregistre);
    }

    @Transactional
    public void softDelete(UUID id) {
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndSupprimeFalse(ids).forEach(x -> x.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndSupprimeTrue(ids).forEach(x -> x.restaurer());
    }

    /** Liste allégée {id, name} pour les sélecteurs (plans d'indexation). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
                .map(x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", x.getId());
                    m.put("name", x.getNomIndex());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private IndexField load(UUID id) {
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
