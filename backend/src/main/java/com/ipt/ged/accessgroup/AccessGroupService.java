package com.ipt.ged.accessgroup;

import com.ipt.ged.accessgroup.dto.AccessGroupRequest;
import com.ipt.ged.accessgroup.dto.AccessGroupResponse;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logique métier des groupes d'accès : CRUD, corbeille et affectation des espaces
 * de travail / utilisateurs. Un groupe organise, il n'autorise rien.
 */
@Service
public class AccessGroupService {

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS = Set.of("id", "code", "name");

    private final AccessGroupRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    private final EmployeRepository employeRepo;
    private final AccessGroupTriParTaille triParTaille;

    public AccessGroupService(AccessGroupRepository repo, WorkSpaceRepository workspaceRepo,
                              EmployeRepository employeRepo, AccessGroupTriParTaille triParTaille) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.employeRepo = employeRepo;
        this.triParTaille = triParTaille;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> list(int page, int size, String search,
                                                  String sortBy, String sortDir) {
        return PageResponse.of(chercher(page, size, search, sortBy, sortDir, false),
                               AccessGroupResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> trashed(int page, int size, String search,
                                                     String sortBy, String sortDir) {
        return PageResponse.of(chercher(page, size, search, sortBy, sortDir, true),
                               AccessGroupResponse::from);
    }

    /** Aiguille vers le tri par cardinalité quand la colonne demandée est une association. */
    private Page<AccessGroup> chercher(int page, int size, String search,
                                       String sortBy, String sortDir, boolean supprimes) {
        String champ = sortBy == null ? "" : sortBy.trim();
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS);
        if (AccessGroupTriParTaille.COLLECTIONS.contains(champ)) {
            Sort.Direction sens = "asc".equalsIgnoreCase(sortDir)
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            return triParTaille.rechercher(search, supprimes, champ, sens, pageable);
        }
        return supprimes
                ? repo.findByDeletedTrueAndNameContainingIgnoreCase(search, pageable)
                : repo.findByDeletedFalseAndNameContainingIgnoreCase(search, pageable);
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

    // Restauration = inverse de la mise en corbeille.
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
