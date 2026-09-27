package com.ipt.ged.workspace;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.dto.TreeNode;
import com.ipt.ged.workspace.dto.WorkSpaceRequest;
import com.ipt.ged.workspace.dto.WorkSpaceResponse;
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
import java.util.Set;
import java.util.UUID;

/**
 * Logique métier des espaces de travail : CRUD, corbeille, déplacement (anti-cycle),
 * archivage, et construction de l'arborescence.
 */
@Service
public class WorkSpaceService {

    /** Journal d'audit des opérations d'administration (DAT §7.4.1). */
    private final JournalAdministration journal;

    /** Colonnes triables de l'écran « Espaces de travail ». */
    private static final Set<String> TRIS = Set.of("id", "code", "name", "status");

    private final WorkSpaceRepository repo;
    private final EmployeRepository employeRepository;
    private final WorkflowRepository workflowRepository;

    public WorkSpaceService(WorkSpaceRepository repo, EmployeRepository employeRepository,
                            WorkflowRepository workflowRepository,
                            JournalAdministration journal) {
        this.journal = journal;
        this.repo = repo;
        this.employeRepository = employeRepository;
        this.workflowRepository = workflowRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkSpaceResponse> list(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkSpace> result = repo.findByDeletedFalseAndNameContainingIgnoreCase(
                search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, this::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkSpaceResponse> trashed(int page, int size, String search, String sortBy, String sortDir) {
        Page<WorkSpace> result = repo.findByDeletedTrueAndNameContainingIgnoreCase(
                search, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, this::toResponse);
    }

    @Transactional(readOnly = true)
    public WorkSpaceResponse get(UUID id) {
        return toResponse(load(id));
    }

    @Transactional
    public WorkSpaceResponse create(WorkSpaceRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        WorkSpace w = new WorkSpace(req.name(), req.code());
        apply(w, req);
        WorkSpaceResponse cree = toResponse(repo.save(w));
        journal.cree(ActionAudit.ESPACE_CREE, "ESPACE", cree.id(), cree);
        return cree;
    }

    @Transactional
    public WorkSpaceResponse update(UUID id, WorkSpaceRequest req) {
        WorkSpace w = loadPourEcriture(id);
        WorkSpaceResponse avant = toResponse(w);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        w.setName(req.name());
        w.setCode(req.code());
        apply(w, req);
        WorkSpaceResponse apres = toResponse(repo.save(w));
        journal.modifie(ActionAudit.ESPACE_MODIFIE, "ESPACE", id, avant, apres);
        return apres;
    }

    @Transactional
    public void softDelete(UUID id) {
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
        journal.action(ActionAudit.ESPACE_SUPPRIME, "ESPACE", id);
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
        journal.action(ActionAudit.ESPACE_RESTAURE, "ESPACE", id);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndDeletedFalse(ids).forEach(w -> {
            w.mettreEnCorbeille(ActeurCourant.employeId());
            journal.action(ActionAudit.ESPACE_SUPPRIME, "ESPACE", w.getId());
        });
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(w -> {
            w.restaurer();
            journal.action(ActionAudit.ESPACE_RESTAURE, "ESPACE", w.getId());
        });
    }

    /** Déplace un dossier sous un nouveau parent (null = racine), en interdisant les cycles. */
    @Transactional
    public WorkSpaceResponse move(UUID id, UUID newParentId) {
        WorkSpace w = loadPourEcriture(id);
        UUID ancienParent = w.getParent() != null ? w.getParent().getId() : null;
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new IllegalArgumentException("Un dossier ne peut pas être son propre parent");
            }
            if (descendantIds(id).contains(newParentId)) {
                throw new IllegalArgumentException("Un dossier ne peut pas être déplacé dans sa propre descendance");
            }
            WorkSpace parent = repo.findById(newParentId)
                    .orElseThrow(() -> new EntityNotFoundException("Dossier parent introuvable : " + newParentId));
            w.setParent(parent);
        } else {
            w.setParent(null);
        }
        WorkSpaceResponse deplace = toResponse(repo.save(w));
        journal.action(ActionAudit.ESPACE_DEPLACE, "ESPACE", id,
                java.util.Collections.singletonMap("parentId", ancienParent),
                java.util.Collections.singletonMap("parentId", newParentId));
        return deplace;
    }

    /** Bascule ACTIF <-> ARCHIVE. */
    @Transactional
    public WorkSpaceResponse archiveToggle(UUID id) {
        WorkSpace w = loadPourEcriture(id);
        w.setStatus(w.getStatus() == WorkspaceStatus.ARCHIVE ? WorkspaceStatus.ACTIF : WorkspaceStatus.ARCHIVE);
        WorkSpaceResponse bascule = toResponse(repo.save(w));
        journal.action(w.getStatus() == WorkspaceStatus.ARCHIVE ? ActionAudit.ESPACE_ARCHIVE : ActionAudit.ESPACE_DESARCHIVE,
                "ESPACE", id);
        return bascule;
    }

    /** Forêt de dossiers actifs (racines + enfants imbriqués). */
    @Transactional(readOnly = true)
    public List<TreeNode> tree() {
        List<WorkSpace> all = repo.findByDeletedFalseOrderByIdAsc().stream()
                .filter(w -> w.getStatus() == WorkspaceStatus.ACTIF)
                .toList();

        Map<UUID, TreeNodeBuilder> byId = new LinkedHashMap<>();
        for (WorkSpace w : all) {
            UUID parentId = w.getParent() != null ? w.getParent().getId() : null;
            byId.put(w.getId(), new TreeNodeBuilder(w.getId(), w.getName(), w.getStatus().name(), parentId));
        }
        List<TreeNodeBuilder> roots = new ArrayList<>();
        for (TreeNodeBuilder b : byId.values()) {
            if (b.parentId != null && byId.containsKey(b.parentId)) {
                byId.get(b.parentId).children.add(b);
            } else {
                roots.add(b);
            }
        }
        // On fige les records SEULEMENT après avoir relié tous les enfants.
        return roots.stream().map(TreeNodeBuilder::build).toList();
    }

    /** Liste allégée {id, name} pour les sélecteurs (parent). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findByDeletedFalseOrderByIdAsc().stream()
                .map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", w.getId());
                    m.put("name", w.getName());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private WorkSpace load(UUID id) {
        return repo.findWithRefsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Espace de travail introuvable : " + id));
    }

    /**
     * Chargement pour écriture : même règle de corbeille que pour les documents
     * — la fiche reste consultable (l'écran « supprimés » doit pouvoir la
     * décrire avant restauration), mais plus rien n'y est modifiable. Renommer,
     * déplacer ou archiver un dossier que l'utilisateur croit supprimé revient à
     * travailler sur une organisation qui n'apparaît nulle part.
     */
    private WorkSpace loadPourEcriture(UUID id) {
        WorkSpace w = load(id);
        if (w.isDeleted()) {
            throw new IllegalArgumentException(
                    "Espace de travail en corbeille : modification impossible. Restaurez-le d'abord.");
        }
        return w;
    }

    /** Ids de toute la descendance d'un dossier (parcours en largeur via la base). */
    private java.util.Set<UUID> descendantIds(UUID id) {
        java.util.Set<UUID> result = new java.util.HashSet<>();
        java.util.Deque<UUID> queue = new java.util.ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (WorkSpace child : repo.findByParentIdAndDeletedFalse(current)) {
                if (result.add(child.getId())) {
                    queue.add(child.getId());
                }
            }
        }
        return result;
    }

    private void apply(WorkSpace w, WorkSpaceRequest req) {
        w.setDescription(req.description());
        w.setStatus(req.status() != null ? req.status() : WorkspaceStatus.ACTIF);

        Employe owner = employeRepository.findById(req.employeId())
                .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + req.employeId()));
        w.setOwner(owner);

        WorkflowGed workflow = workflowRepository.findById(req.workflowId())
                .orElseThrow(() -> new EntityNotFoundException("Règle de workflow introuvable : " + req.workflowId()));
        w.setWorkflow(workflow);

        if (req.parentId() != null) {
            WorkSpace parent = repo.findById(req.parentId())
                    .orElseThrow(() -> new EntityNotFoundException("Dossier parent introuvable : " + req.parentId()));
            w.setParent(parent);
        } else {
            w.setParent(null);
        }
    }

    private WorkSpaceResponse toResponse(WorkSpace w) {
        return WorkSpaceResponse.from(w, repo.countByParentIdAndDeletedFalse(w.getId()));
    }

    /** Petit builder mutable pour assembler l'arbre avant de figer les records. */
    private static final class TreeNodeBuilder {
        final UUID id;
        final String name;
        final String status;
        final UUID parentId;
        final List<TreeNodeBuilder> children = new ArrayList<>();

        TreeNodeBuilder(UUID id, String name, String status, UUID parentId) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.parentId = parentId;
        }

        TreeNode build() {
            return new TreeNode(id, name, status, parentId,
                    children.stream().map(TreeNodeBuilder::build).toList());
        }
    }
}
