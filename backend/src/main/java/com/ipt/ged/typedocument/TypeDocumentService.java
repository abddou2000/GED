package com.ipt.ged.typedocument;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.typedocument.dto.TypeDocumentRequest;
import com.ipt.ged.typedocument.dto.TypeDocumentResponse;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
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
 * Logique métier des types de document : CRUD, corbeille, rattachement à un
 * espace de travail (obligatoire) et à un plan d'indexation (facultatif).
 */
@Service
public class TypeDocumentService {

    private final TypeDocumentRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS =
            Set.of("id", "code", "typeDeDocument", "workspace.name", "tailleMaxMo");

    /** Colonnes numériques : triées telles quelles, sans passage en minuscules. */
    private static final Set<String> TRIS_NUM = Set.of("id", "tailleMaxMo");

    private final PlanIndexationRepository planRepo;

    public TypeDocumentService(TypeDocumentRepository repo, WorkSpaceRepository workspaceRepo,
                               PlanIndexationRepository planRepo) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.planRepo = planRepo;
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> list(int page, int size, String search,
                                                   String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<TypeDocument> result = repo.findByDeletedFalseAndTypeDeDocumentContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> trashed(int page, int size, String search,
                                                      String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<TypeDocument> result = repo.findByDeletedTrueAndTypeDeDocumentContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public TypeDocumentResponse get(UUID id) {
        return TypeDocumentResponse.from(load(id));
    }

    // Aucune chaîne « create type_de_document » au catalogue : repli sur le
    // statut administrateur, décision par défaut à arbitrer.
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
    public TypeDocumentResponse update(UUID id, TypeDocumentRequest req) {
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
        repo.findByIdInAndDeletedFalse(ids).forEach(t -> t.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(t -> t.restaurer());
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
                    /* Contraintes du type, servies avec la liste de choix : le
                       formulaire de depot doit pouvoir refuser un fichier AVANT
                       de l'envoyer. Sans elles, un fichier de 2,6 Go partait sur
                       le reseau pour n'etre rejete qu'a l'arrivee — et la
                       connexion etant coupee en cours d'envoi, le navigateur
                       n'affichait meme pas le motif du refus. */
                    /* Charte automatique : le nom du document sera COMPOSÉ depuis
                       les index à la confirmation de l'indexation. Le formulaire
                       de dépôt s'en sert pour ne pas faire saisir un nom qu'il
                       remplacera juste après. */
                    m.put("charteAuto", t.getPlanIndexation() != null && !t.getPlanIndexation().isManuel());
                    m.put("formats", t.formatsAutorises());
                    m.put("tailleMaxMo", t.getTailleMaxMo());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private TypeDocument load(UUID id) {
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
