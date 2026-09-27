package com.ipt.ged.etiquette;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.common.ActeurCourant;
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
import java.util.UUID;

/**
 * Logique métier des étiquettes : CRUD simple + corbeille.
 */
@Service
public class EtiquetteService {

    /** Journal d'audit des opérations d'administration (DAT §7.4.1). */
    private final JournalAdministration journal;

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */

    private static final Set<String> TRIS = Set.of("id", "code", "tag");


    private final EtiquetteRepository repo;

    public EtiquetteService(EtiquetteRepository repo,
                            JournalAdministration journal) {
        this.journal = journal;
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public PageResponse<EtiquetteResponse> list(int page, int size, String search,
                                                String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<Etiquette> result = repo.findBySupprimeFalseAndTagContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, EtiquetteResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<EtiquetteResponse> trashed(int page, int size, String search,
                                                   String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        Page<Etiquette> result = repo.findBySupprimeTrueAndTagContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, EtiquetteResponse::from);
    }

    @Transactional(readOnly = true)
    public EtiquetteResponse get(UUID id) {
        return EtiquetteResponse.from(load(id));
    }

    @Transactional
    public EtiquetteResponse create(EtiquetteRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        Etiquette e = new Etiquette(req.code(), req.tag(), req.couleur());
        EtiquetteResponse cree = EtiquetteResponse.from(repo.save(e));
        journal.cree(ActionAudit.ETIQUETTE_CREEE, "ETIQUETTE", cree.id(), cree);
        return cree;
    }

    @Transactional
    public EtiquetteResponse update(UUID id, EtiquetteRequest req) {
        Etiquette e = load(id);
        EtiquetteResponse avant = EtiquetteResponse.from(e);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        e.setCode(req.code());
        e.setTag(req.tag());
        e.setCouleur(req.couleur());
        EtiquetteResponse apres = EtiquetteResponse.from(repo.save(e));
        journal.modifie(ActionAudit.ETIQUETTE_MODIFIEE, "ETIQUETTE", id, avant, apres);
        return apres;
    }

    @Transactional
    public void softDelete(UUID id) {
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
        journal.action(ActionAudit.ETIQUETTE_SUPPRIMEE, "ETIQUETTE", id);
    }

    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
        journal.action(ActionAudit.ETIQUETTE_RESTAUREE, "ETIQUETTE", id);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndSupprimeFalse(ids).forEach(e -> {
            e.mettreEnCorbeille(ActeurCourant.employeId());
            journal.action(ActionAudit.ETIQUETTE_SUPPRIMEE, "ETIQUETTE", e.getId());
        });
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndSupprimeTrue(ids).forEach(e -> {
            e.restaurer();
            journal.action(ActionAudit.ETIQUETTE_RESTAUREE, "ETIQUETTE", e.getId());
        });
    }

    /** Liste allégée {id, name} pour les sélecteurs (upload). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("name", e.getTag());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private Etiquette load(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Étiquette introuvable : " + id));
    }
}
