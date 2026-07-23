package com.ipt.ged.accessgroup;

import com.ipt.ged.accessgroup.dto.AccessGroupRequest;
import com.ipt.ged.accessgroup.dto.AccessGroupResponse;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
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
 * Logique métier des groupes d'accès : CRUD, corbeille, normalisation des 8 droits
 * (cascade de dépendances) et affectation des espaces de travail / utilisateurs.
 */
@Service
public class AccessGroupService {

    private final AccessGroupRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    private final EmployeRepository employeRepo;

    public AccessGroupService(AccessGroupRepository repo, WorkSpaceRepository workspaceRepo,
                              EmployeRepository employeRepo) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.employeRepo = employeRepo;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> list(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AccessGroup> result = repo.findByDeletedFalseAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, AccessGroupResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AccessGroup> result = repo.findByDeletedTrueAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, AccessGroupResponse::from);
    }

    @Transactional(readOnly = true)
    public AccessGroupResponse get(Long id) {
        return AccessGroupResponse.from(load(id));
    }

    @Transactional
    public AccessGroupResponse create(AccessGroupRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        if (repo.existsByNameIgnoreCase(req.name())) {
            throw new IllegalArgumentException("Le nom « " + req.name() + " » est déjà utilisé");
        }
        AccessGroup g = new AccessGroup(req.code(), req.name());
        apply(g, req);
        return AccessGroupResponse.from(repo.save(g));
    }

    @Transactional
    public AccessGroupResponse update(Long id, AccessGroupRequest req) {
        AccessGroup g = load(id);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        if (repo.existsByNameIgnoreCaseAndIdNot(req.name(), id)) {
            throw new IllegalArgumentException("Le nom « " + req.name() + " » est déjà utilisé");
        }
        g.setCode(req.code());
        g.setName(req.name());
        apply(g, req);
        return AccessGroupResponse.from(repo.save(g));
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
        repo.findByIdInAndDeletedFalse(ids).forEach(g -> g.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(g -> g.setDeleted(false));
    }

    /** Liste allégée {id, name} pour les sélecteurs. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", g.getId());
                    m.put("name", g.getName());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private AccessGroup load(Long id) {
        return repo.findWithRefsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Groupe d'accès introuvable : " + id));
    }

    private void apply(AccessGroup g, AccessGroupRequest req) {
        // Droits : recopie + normalisation (ferme les dépendances)
        GedRights rights = g.getRights() != null ? g.getRights() : new GedRights();
        if (req.rights() != null) {
            req.rights().applyTo(rights);
        }
        rights.normalize();
        g.setRights(rights);

        // Espaces de travail
        List<Long> wsIds = req.workspaceIds() != null ? req.workspaceIds() : List.of();
        List<WorkSpace> ws = wsIds.isEmpty() ? List.of() : workspaceRepo.findAllById(wsIds);
        g.getWorkspaces().clear();
        g.getWorkspaces().addAll(ws);

        // Membres (utilisateurs)
        List<Long> userIds = req.userIds() != null ? req.userIds() : List.of();
        List<Employe> users = userIds.isEmpty() ? List.of() : employeRepo.findAllById(userIds);
        g.getUsers().clear();
        g.getUsers().addAll(users);
    }
}
