package com.ipt.ged.typedocument;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
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

    /** Journal d'audit des opérations d'administration (DAT §7.4.1). */
    private final JournalAdministration journal;

    private final TypeDocumentRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS =
            Set.of("id", "code", "typeDeDocument", "workspace.name", "tailleMaxMo");

    /** Colonnes numériques : triées telles quelles, sans passage en minuscules. */
    private static final Set<String> TRIS_NUM = Set.of("id", "tailleMaxMo");

    private final PlanIndexationRepository planRepo;
    private final com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versionsPlan;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public TypeDocumentService(TypeDocumentRepository repo, WorkSpaceRepository workspaceRepo,
                               PlanIndexationRepository planRepo,
                               JournalAdministration journal,
                               com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan versionsPlan,
                               org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.journal = journal;
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.planRepo = planRepo;
        this.versionsPlan = versionsPlan;
        this.jdbc = jdbc;
    }

    /** Réponse avec la version en vigueur du plan (créée si le plan a changé). */
    private TypeDocumentResponse reponse(TypeDocument t) {
        Integer version = versionsPlan.enVigueur(t)
                .map(com.ipt.ged.planindexation.metamodele.PlanIndexationVersion::getNumero).orElse(null);
        return TypeDocumentResponse.from(t, version);
    }

    /** Le type porte-t-il des documents (corbeille comprise) ? */
    private boolean utilise(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document WHERE type_document_id = ?)", Boolean.class, id));
    }

    /**
     * Active ou désactive un type (§12.7) : un type utilisé ne se supprime pas,
     * il se désactive — plus aucun dépôt, ses documents restent intacts.
     */
    @Transactional
    public TypeDocumentResponse activer(UUID id, boolean actif) {
        TypeDocument t = load(id);
        t.setActif(actif);
        return reponse(repo.save(t));
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> list(int page, int size, String search,
                                                   String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<TypeDocument> result = repo.findBySupprimeFalseAndTypeDeDocumentContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<TypeDocumentResponse> trashed(int page, int size, String search,
                                                      String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<TypeDocument> result = repo.findBySupprimeTrueAndTypeDeDocumentContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, TypeDocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public TypeDocumentResponse get(UUID id) {
        TypeDocument t = load(id);
        Integer version = t.getPlanIndexation() == null ? null : versionsPlan.derniereVersion(t.getPlanIndexation().getId());
        return TypeDocumentResponse.from(t, version);
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
        TypeDocumentResponse cree = reponse(repo.save(t));
        journal.cree(ActionAudit.TYPE_DOCUMENT_CREE, "TYPE_DOCUMENT", cree.id(), cree);
        return cree;
    }

    @Transactional
    public TypeDocumentResponse update(UUID id, TypeDocumentRequest req) {
        TypeDocument t = load(id);
        // Même forme que la réponse (version du plan comprise) : l'audit ne
        // relève que ce qui a réellement changé.
        TypeDocumentResponse avant = reponse(t);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        t.setCode(req.code());
        t.setTypeDeDocument(req.typeDeDocument());
        apply(t, req);
        // Toute modification du type produit, si son plan a changé, une nouvelle
        // version : les documents déjà déposés gardent la leur (§12.7).
        TypeDocumentResponse apres = reponse(repo.saveAndFlush(t));
        journal.modifie(ActionAudit.TYPE_DOCUMENT_MODIFIE, "TYPE_DOCUMENT", id, avant, apres);
        return apres;
    }

    @Transactional
    public void softDelete(UUID id) {
        TypeDocument t = load(id);
        refuserSiUtilise(t);
        t.mettreEnCorbeille(ActeurCourant.employeId());
        journal.action(ActionAudit.TYPE_DOCUMENT_SUPPRIME, "TYPE_DOCUMENT", id);
    }

    /**
     * Suppression d'un type utilisé impossible (§12.7) : contrôle applicatif,
     * doublé en base par la clé étrangère RESTRICT. Le type se désactive.
     */
    private void refuserSiUtilise(TypeDocument t) {
        if (utilise(t.getId())) {
            throw new com.ipt.ged.autorisation.ConflitAutorisationException("TYPE_UTILISE",
                    "Le type « " + t.getTypeDeDocument() + " » porte des documents : il ne peut pas être supprimé,"
                            + " seulement désactivé.");
        }
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
        journal.action(ActionAudit.TYPE_DOCUMENT_RESTAURE, "TYPE_DOCUMENT", id);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        List<TypeDocument> l = repo.findByIdInAndSupprimeFalse(ids);
        l.forEach(this::refuserSiUtilise);
        l.forEach(t -> {
            t.mettreEnCorbeille(ActeurCourant.employeId());
            journal.action(ActionAudit.TYPE_DOCUMENT_SUPPRIME, "TYPE_DOCUMENT", t.getId());
        });
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndSupprimeTrue(ids).forEach(t -> {
            t.restaurer();
            journal.action(ActionAudit.TYPE_DOCUMENT_RESTAURE, "TYPE_DOCUMENT", t.getId());
        });
    }

    /** Liste allégée {id, name} pour les sélecteurs (upload). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
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

        // Conservation (§12.9) : l'échéance des documents du type est
        // recalculée par la base si la durée ou le point de départ change.
        t.setDureeConservationMois(req.dureeConservationMois());
        PointDepart depart = req.pointDepart() != null ? req.pointDepart() : PointDepart.DATE_DOCUMENT;
        String code = null;
        if (depart == PointDepart.METADONNEE) {
            if (req.pointDepartIndexCode() == null || req.pointDepartIndexCode().isBlank()) {
                throw new IllegalArgumentException("Point de départ « métadonnée » : l'index date est obligatoire.");
            }
            code = t.getPlanIndexation() == null ? null : t.getPlanIndexation().getIndices().stream()
                    .filter(i -> !i.isSupprime() && i.getCode().equalsIgnoreCase(req.pointDepartIndexCode().trim()))
                    .filter(i -> i.getFieldType() == com.ipt.ged.index.IndexFieldType.DATE)
                    .map(com.ipt.ged.index.IndexField::getCode).findFirst().orElse(null);
            if (code == null) {
                throw new IllegalArgumentException("Point de départ : « " + req.pointDepartIndexCode()
                        + " » n'est pas un index de nature date du plan de ce type.");
            }
        }
        t.setPointDepart(depart);
        t.setPointDepartIndexCode(code);
        if (req.confidentialiteDefaut() != null) t.setConfidentialiteDefaut(req.confidentialiteDefaut());
    }
}
