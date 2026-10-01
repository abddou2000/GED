package com.ipt.ged.workspace;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.autorisation.Habilitation;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.autorisation.HorsPerimetreException;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.accessgroup.AccessGroup;
import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.archivage.ArchivageNoeuds;
import com.ipt.ged.workspace.dto.TreeNode;
import com.ipt.ged.workspace.dto.WorkSpaceRequest;
import com.ipt.ged.workspace.dto.WorkSpaceResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Logique métier des nœuds (espaces et dossiers) : CRUD, corbeille, déplacement
 * (anti-cycle), archivage, et construction de l'arborescence.
 *
 * <h2>Droits (lot E3)</h2>
 * <ul>
 *   <li>Lecture (P5) : listes, fiche, sélecteurs et compteurs ne portent que sur
 *       les nœuds que couvre une habilitation de l'appelant ; l'arborescence y
 *       ajoute les ancêtres de ces nœuds, réduits à leur libellé de passage.
 *       Hors périmètre : 404.</li>
 *   <li>Écriture (§12.5) : créer un espace ou gérer n'importe quel nœud exige
 *       la permission d'administration {@code GERER_ESPACES}. Modifier un
 *       espace ou un dossier ({@code PUT} : nom, code, propriétaire, statut,
 *       usage métier / échange, règle de workflow, parent) relève aussi, et
 *       seulement, de la gestion des espaces (DF §4.3.4, D12, ANO-F-026) : la
 *       permission Modifier, que porte l'Utilisateur standard, vaut pour les
 *       documents, pas pour la structure. À défaut de {@code GERER_ESPACES},
 *       archiver exige Archiver, supprimer / restaurer Supprimer, déplacer
 *       Déplacer sur le nœud et Déposer sur la destination. Créer un dossier :
 *       dans un espace MÉTIER, c'est la gestion des espaces ; dans un espace
 *       d'ÉCHANGE (R-03, D12), les membres habilités (Déposer sur le parent)
 *       créent librement dossiers et sous-dossiers.</li>
 *   <li>Renommage : nom unique parmi les nœuds vivants du même parent (409
 *       {@code NOM_DEJA_UTILISE}).</li>
 *   <li>Toute modification de l'arborescence incrémente
 *       {@code version_habilitations} : les droits hérités suivent
 *       immédiatement.</li>
 * </ul>
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
    private final AccessPredicate droits;
    private final ControleAcces controle;
    private final VersionHabilitations version;
    private final HabilitationRepository habilitations;
    private final AccessGroupRepository groupes;
    /** Statut de conservation des nœuds (D10) : aucun nœud ne naît ni n'arrive sous un dossier archivé. */
    private final ArchivageNoeuds archivage;
    /** Portée des clés d'API (lot intégration), résolue à l'usage. */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.ipt.ged.cleapi.ControlePorteeApplication>
            porteeApplications;

    public WorkSpaceService(WorkSpaceRepository repo, EmployeRepository employeRepository,
                            WorkflowRepository workflowRepository, AccessPredicate droits, ControleAcces controle,
                            VersionHabilitations version, HabilitationRepository habilitations,
                            AccessGroupRepository groupes,
                            JournalAdministration journal, ArchivageNoeuds archivage) {
        this.journal = journal;
        this.archivage = archivage;
        this.repo = repo;
        this.employeRepository = employeRepository;
        this.workflowRepository = workflowRepository;
        this.droits = droits;
        this.controle = controle;
        this.version = version;
        this.habilitations = habilitations;
        this.groupes = groupes;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkSpaceResponse> list(int page, int size, String search, String sortBy, String sortDir) {
        return pageDe(false, page, size, search, sortBy, sortDir);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkSpaceResponse> trashed(int page, int size, String search, String sortBy, String sortDir) {
        return pageDe(true, page, size, search, sortBy, sortDir);
    }

    private PageResponse<WorkSpaceResponse> pageDe(boolean supprimes, int page, int size, String search,
                                                   String sortBy, String sortDir) {
        String motif = "%" + (search == null ? "" : search).toLowerCase() + "%";
        Specification<WorkSpace> spec = (r, q, cb) -> cb.and(cb.equal(r.get("supprime"), supprimes),
                cb.like(cb.lower(r.get("name")), motif));
        spec = spec.and(perimetre());
        Page<WorkSpace> result = repo.findAll(spec, Tri.pageable(page, size, sortBy, sortDir, TRIS));
        return PageResponse.of(result, reponses(result.getContent())::get);
    }

    /** Nœuds couverts par l'appelant (tous pour un gestionnaire d'espaces). */
    private Specification<WorkSpace> perimetre() {
        if (gestionnaire()) return (r, q, cb) -> cb.conjunction();
        Set<UUID> couverts = couverts();
        return (r, q, cb) -> couverts.isEmpty() ? cb.disjunction() : r.get("id").in(couverts);
    }

    @Transactional(readOnly = true)
    public WorkSpaceResponse get(UUID id) {
        WorkSpace w = load(id);
        exigerCouvert(id);
        return reponses(List.of(w)).get(w).avecPermissions(permissionsSurLaFiche(id));
    }

    /**
     * Permissions effectives de l'appelant sur le nœud (ANO-F-018) : la fiche
     * n'affiche que les actions qu'il peut exercer. {@code MODIFIER} y désigne
     * la modification du nœud lui-même, réservée à la gestion des espaces
     * (ANO-F-026, {@link #update}) : elle est retirée à qui ne la détient pas,
     * même si son rôle porte Modifier (qui vaut alors pour les documents, dont
     * la fiche porte ses propres permissions), et ajoutée au gestionnaire.
     */
    private List<String> permissionsSurLaFiche(UUID id) {
        Set<CodePermission> p = java.util.EnumSet.noneOf(CodePermission.class);
        p.addAll(controle.droits().surNoeud(id));
        if (gestionnaire()) p.add(CodePermission.MODIFIER);
        else p.remove(CodePermission.MODIFIER);
        return p.stream().map(Enum::name).sorted().toList();
    }

    @Transactional
    public WorkSpaceResponse create(WorkSpaceRequest req) {
        if (req.parentId() == null) {
            controle.exigerAdministration(CodePermission.GERER_ESPACES);
        } else if (!gestionnaire()) {
            WorkSpace parent = repo.findById(req.parentId())
                    .orElseThrow(() -> new EntityNotFoundException("Dossier parent introuvable : " + req.parentId()));
            controle.exigerSurNoeud(CodePermission.DEPOSER, parent.getId());
            if (parent.getUsageEspace() != UsageEspace.ECHANGE && !creationParApplication(parent.getId())) {
                // Espace métier : son organisation relève de la gestion des espaces.
                controle.exigerAdministration(CodePermission.GERER_ESPACES);
            }
        }
        refuserSiDossierArchive(req.parentId());
        exigerNomLibre(req.name(), req.parentId(), null);
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        WorkSpace w = new WorkSpace(req.name(), req.code());
        apply(w, req);
        WorkSpace cree = repo.saveAndFlush(w);
        version.incrementer();
        WorkSpaceResponse reponse = reponses(List.of(cree)).get(cree);
        journal.cree(ActionAudit.ESPACE_CREE, "ESPACE", reponse.id(), reponse);
        return reponse;
    }

    @Transactional
    public WorkSpaceResponse update(UUID id, WorkSpaceRequest req) {
        WorkSpace w = loadPourEcriture(id);
        exigerGestionDuNoeud(id);
        WorkSpaceResponse avant = reponses(List.of(w)).get(w);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        UUID ancienParent = w.getParent() != null ? w.getParent().getId() : null;
        if (!Objects.equals(ancienParent, req.parentId())) {
            // Changer de parent par la fiche est un déplacement : mêmes droits, même garde.
            verifierDeplacement(id, req.parentId());
            refuserSiDossierArchive(req.parentId());
        }
        if (!req.name().trim().equalsIgnoreCase(w.getName()) || !Objects.equals(ancienParent, req.parentId())) {
            exigerNomLibre(req.name(), req.parentId(), id);
        }
        w.setName(req.name());
        w.setCode(req.code());
        apply(w, req);
        WorkSpace maj = repo.saveAndFlush(w);
        version.incrementer();
        WorkSpaceResponse apres = reponses(List.of(maj)).get(maj);
        journal.modifie(ActionAudit.ESPACE_MODIFIE, "ESPACE", id, avant, apres);
        return apres;
    }

    @Transactional
    public void softDelete(UUID id) {
        WorkSpace w = load(id);
        exiger(CodePermission.SUPPRIMER, id);
        mettreEnCorbeille(w);
        journal.action(ActionAudit.ESPACE_SUPPRIME, "ESPACE", id);
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        WorkSpace w = load(id);
        exiger(CodePermission.SUPPRIMER, id);
        restaurer(w);
        journal.action(ActionAudit.ESPACE_RESTAURE, "ESPACE", id);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        List<WorkSpace> l = repo.findByIdInAndSupprimeFalse(ids);
        l.forEach(w -> exiger(CodePermission.SUPPRIMER, w.getId()));
        l.forEach(this::mettreEnCorbeille);
        l.forEach(w -> journal.action(ActionAudit.ESPACE_SUPPRIME, "ESPACE", w.getId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        List<WorkSpace> l = repo.findByIdInAndSupprimeTrue(ids);
        l.forEach(w -> exiger(CodePermission.SUPPRIMER, w.getId()));
        l.forEach(this::restaurer);
        l.forEach(w -> journal.action(ActionAudit.ESPACE_RESTAURE, "ESPACE", w.getId()));
    }

    /**
     * Suppression douce du nœud ET de sa sous-arborescence (§12.5) : même auteur,
     * même horodatage, ce qui permet à la restauration de ne ramener que ce que
     * cette suppression a emporté. Aucune donnée n'est effacée.
     */
    private void mettreEnCorbeille(WorkSpace w) {
        if (w.isSupprime()) return;
        UUID auteur = ActeurCourant.employeId();
        w.mettreEnCorbeille(auteur);
        Instant le = w.getSupprimeLe();
        for (WorkSpace d : repo.findByCheminStartingWithAndIdNot(w.getChemin(), w.getId())) {
            if (!d.isSupprime()) d.mettreEnCorbeille(auteur, le);
        }
        version.incrementer();
    }

    /** Restaure le nœud et les descendants supprimés avec lui (même horodatage). */
    private void restaurer(WorkSpace w) {
        if (!w.isSupprime()) return;
        Instant le = w.getSupprimeLe();
        for (WorkSpace d : repo.findByCheminStartingWithAndIdNot(w.getChemin(), w.getId())) {
            if (d.isSupprime() && Objects.equals(d.getSupprimeLe(), le)) d.restaurer();
        }
        w.restaurer();
        version.incrementer();
    }

    /** Déplace un dossier sous un nouveau parent (null = racine), en interdisant les cycles. */
    @Transactional
    public WorkSpaceResponse move(UUID id, UUID newParentId) {
        WorkSpace w = loadPourEcriture(id);
        verifierDeplacement(id, newParentId);
        refuserSiDossierArchive(newParentId);
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
            exigerNomLibre(w.getName(), newParentId, id);
            w.setParent(parent);
        } else {
            exigerNomLibre(w.getName(), null, id);
            w.setParent(null);
        }
        // Le chemin de toute la sous-arborescence est recalculé par la base.
        WorkSpace deplace = repo.saveAndFlush(w);
        version.incrementer();
        journal.action(ActionAudit.ESPACE_DEPLACE, "ESPACE", id,
                java.util.Collections.singletonMap("parentId", ancienParent),
                java.util.Collections.singletonMap("parentId", newParentId));
        return reponses(List.of(deplace)).get(deplace);
    }

    /** Déplacer : Déplacer sur le nœud, Déposer sur la destination ; racine : gestion des espaces. */
    private void verifierDeplacement(UUID id, UUID destination) {
        if (gestionnaire()) return;
        controle.exigerSurNoeud(CodePermission.DEPLACER, id);
        if (destination == null) {
            controle.exigerAdministration(CodePermission.GERER_ESPACES);
        } else {
            controle.exigerSurNoeud(CodePermission.DEPOSER, destination);
        }
    }

    /**
     * ANO-E7-005 (D10, §12.6) : un dossier archivé est en lecture seule, sa
     * sous-arborescence comprise (le drapeau la couvre, {@link ArchivageNoeuds#marquerArchive}).
     * Un sous-dossier créé ou déplacé dessous naîtrait ACTIF et accepterait des
     * dépôts, ce qui contournait le refus du dépôt : même refus, même code
     * (409 {@code DOSSIER_ARCHIVE}, {@code DocumentService}). Les droits sont
     * vérifiés avant : un appelant hors périmètre n'apprend pas le statut.
     */
    private void refuserSiDossierArchive(UUID parentId) {
        if (parentId == null) return;
        if (archivage.statut(parentId) == com.ipt.ged.common.StatutConservation.ARCHIVE) {
            String nom = repo.findById(parentId).map(WorkSpace::getName).orElse("");
            throw new com.ipt.ged.cycledevie.ErreurCycleDeVie(org.springframework.http.HttpStatus.CONFLICT,
                    com.ipt.ged.cycledevie.ErreurCycleDeVie.DOSSIER_ARCHIVE,
                    "Dossier archivé « " + nom + " » : aucun dossier ne peut y être créé ni déplacé.");
        }
    }

    /** Bascule ACTIF <-> ARCHIVE. */
    @Transactional
    public WorkSpaceResponse archiveToggle(UUID id) {
        WorkSpace w = loadPourEcriture(id);
        exiger(CodePermission.ARCHIVER, id);
        w.setStatus(w.getStatus() == WorkspaceStatus.ARCHIVE ? WorkspaceStatus.ACTIF : WorkspaceStatus.ARCHIVE);
        WorkSpace maj = repo.save(w);
        journal.action(w.getStatus() == WorkspaceStatus.ARCHIVE ? ActionAudit.ESPACE_ARCHIVE : ActionAudit.ESPACE_DESARCHIVE,
                "ESPACE", id);
        return reponses(List.of(maj)).get(maj);
    }

    /**
     * Arborescence des dossiers actifs VISIBLES (P5) : nœuds couverts par une
     * habilitation de l'appelant, et leurs ancêtres réduits à leur libellé de
     * passage (ni statut, ni compteur, ni action).
     */
    @Transactional(readOnly = true)
    public List<TreeNode> tree() {
        Map<UUID, Boolean> visibles = droits.noeudsVisibles(SecurityContextHolder.getContext().getAuthentication());
        List<WorkSpace> all = repo.findBySupprimeFalseOrderByIdAsc().stream()
                .filter(w -> w.getStatus() == WorkspaceStatus.ACTIF)
                .filter(w -> visibles.containsKey(w.getId()))
                .toList();

        Map<UUID, TreeNodeBuilder> byId = new LinkedHashMap<>();
        for (WorkSpace w : all) {
            UUID parentId = w.getParent() != null ? w.getParent().getId() : null;
            boolean passage = !visibles.get(w.getId());
            byId.put(w.getId(), new TreeNodeBuilder(w.getId(), w.getName(),
                    passage ? null : w.getStatus().name(), parentId, passage));
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

    /** Liste allégée {id, name} pour les sélecteurs : nœuds couverts seulement. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        boolean tous = gestionnaire();
        Set<UUID> couverts = tous ? Set.of() : couverts();
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
                .filter(w -> tous || couverts.contains(w.getId()))
                .map(w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", w.getId());
                    m.put("name", w.getName());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private boolean gestionnaire() {
        return controle.administre(CodePermission.GERER_ESPACES);
    }

    /**
     * Une application dont la clé porte l'opération {@code CREATION_DOSSIER} sur
     * le parent (portée posée par l'Administrateur, DAT §5.4) crée des dossiers
     * dans un espace métier : cette portée est la décision de gestion des
     * espaces pour l'intégration. Seule la portée de la clé est lue ici ; les
     * droits (ceux de la personne déléguée compris) ont déjà été exigés par
     * {@code AccessPredicate} (Déposer sur le parent).
     */
    private boolean creationParApplication(UUID parentId) {
        if (!(SecurityContextHolder.getContext().getAuthentication()
                instanceof com.ipt.ged.cleapi.ApplicationAuthentifiee application)) return false;
        com.ipt.ged.cleapi.ControlePorteeApplication portee =
                porteeApplications != null ? porteeApplications.getIfAvailable() : null;
        if (portee == null) return false;
        portee.verifier(application, com.ipt.ged.cleapi.OperationApi.CREATION_DOSSIER, parentId);
        return true;
    }

    /** Nœuds où l'appelant détient au moins une permission. */
    private Set<UUID> couverts() {
        return droits.noeudsVisibles(SecurityContextHolder.getContext().getAuthentication()).entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private void exigerCouvert(UUID id) {
        if (!gestionnaire() && !couverts().contains(id)) {
            throw controle.horsPerimetre("NOEUD", id, "Espace de travail introuvable : " + id);
        }
    }

    /**
     * Modifier un espace ou un dossier (ANO-F-026, DF §4.3.4, D12) : la
     * structure (nom, code, propriétaire, statut, usage, règle de workflow,
     * parent) relève de la gestion des espaces, comme la création. La
     * permission Modifier sur le nœud ne suffit plus : un Utilisateur standard
     * basculait ainsi un espace métier en espace d'échange pour s'y ouvrir la
     * création de dossiers. Hors périmètre : 404 tracé ; visible : 403 tracé
     * {@code ACCES_REFUSE} (gestionnaire d'exceptions). Aucune exception D12 :
     * dans un espace d'échange, le membre crée des dossiers
     * ({@link #create}), il ne les restructure pas.
     */
    private void exigerGestionDuNoeud(UUID id) {
        if (gestionnaire()) return;
        controle.exigerNoeudVisible(id);
        controle.exigerAdministration(CodePermission.GERER_ESPACES);
    }

    private void exiger(CodePermission p, UUID noeudId) {
        if (!gestionnaire()) controle.exigerSurNoeud(p, noeudId);
    }

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
        if (w.isSupprime()) {
            exigerCouvert(id);
            throw new IllegalArgumentException(
                    "Espace de travail en corbeille : modification impossible. Restaurez-le d'abord.");
        }
        return w;
    }

    /** Ids de toute la descendance d'un dossier, lus sur le chemin matérialisé. */
    private Set<UUID> descendantIds(UUID id) {
        WorkSpace w = load(id);
        return repo.findByCheminStartingWithAndIdNot(w.getChemin(), id).stream()
                .map(WorkSpace::getId).collect(Collectors.toSet());
    }

    /**
     * Unicité du nom parmi les nœuds vivants d'un même parent (les espaces
     * entre eux) : 409 NOM_DEJA_UTILISE (§12.5).
     */
    private void exigerNomLibre(String nom, UUID parentId, UUID sauf) {
        String n = nom == null ? "" : nom.trim();
        boolean pris = repo.findAll((r, q, cb) -> cb.and(
                        cb.isFalse(r.get("supprime")),
                        cb.equal(cb.lower(r.get("name")), n.toLowerCase()),
                        parentId == null ? cb.isNull(r.get("parent")) : cb.equal(r.get("parent").get("id"), parentId),
                        sauf == null ? cb.conjunction() : cb.notEqual(r.get("id"), sauf)))
                .stream().findAny().isPresent();
        if (pris) {
            throw new com.ipt.ged.autorisation.ConflitAutorisationException("NOM_DEJA_UTILISE",
                    "Un dossier « " + n + " » existe déjà à cet emplacement.");
        }
    }

    private void apply(WorkSpace w, WorkSpaceRequest req) {
        w.setDescription(req.description());
        if (req.usageEspace() != null) w.setUsageEspace(req.usageEspace());
        w.setStatus(req.status() != null ? req.status() : WorkspaceStatus.ACTIF);

        Employe owner = employeRepository.findById(req.employeId())
                .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + req.employeId()));
        w.setOwner(owner);

        WorkflowGed workflow = req.workflowId() == null ? null : workflowRepository.findById(req.workflowId())
                .orElseThrow(() -> new EntityNotFoundException("Règle de workflow introuvable : " + req.workflowId()));
        w.setWorkflow(workflow);

        if (req.parentId() != null) {
            if (w.getId() != null && (req.parentId().equals(w.getId())
                    || descendantIds(w.getId()).contains(req.parentId()))) {
                throw new IllegalArgumentException("Un dossier ne peut pas être déplacé dans sa propre descendance");
            }
            WorkSpace parent = repo.findById(req.parentId())
                    .orElseThrow(() -> new EntityNotFoundException("Dossier parent introuvable : " + req.parentId()));
            w.setParent(parent);
        } else {
            w.setParent(null);
        }
    }

    /**
     * Réponses d'une page de nœuds : sous-dossiers VISIBLES comptés (P5) et
     * groupes GED habilités.
     */
    private Map<WorkSpace, WorkSpaceResponse> reponses(List<WorkSpace> page) {
        Map<UUID, Boolean> visibles = gestionnaire() ? null
                : droits.noeudsVisibles(SecurityContextHolder.getContext().getAuthentication());
        Map<UUID, List<WorkSpaceResponse.Ref>> groupesParNoeud = groupesHabilites(
                page.stream().map(WorkSpace::getId).toList());
        Map<WorkSpace, WorkSpaceResponse> m = new LinkedHashMap<>();
        for (WorkSpace w : page) {
            long enfants = repo.findByParentIdAndSupprimeFalse(w.getId()).stream()
                    .filter(e -> visibles == null || visibles.containsKey(e.getId()))
                    .count();
            m.put(w, WorkSpaceResponse.from(w, enfants, groupesParNoeud.getOrDefault(w.getId(), List.of())));
        }
        return m;
    }

    private Map<UUID, List<WorkSpaceResponse.Ref>> groupesHabilites(Collection<UUID> noeuds) {
        if (noeuds.isEmpty()) return Map.of();
        List<Habilitation> hs = habilitations.findByNoeudIdInAndSujetType(noeuds, TypeSujet.GROUPE).stream()
                .filter(h -> h.getRole() != null).toList();
        Map<UUID, AccessGroup> g = new HashMap<>();
        groupes.findAllById(hs.stream().map(Habilitation::getGroupeGedId).distinct().toList())
                .forEach(x -> g.put(x.getId(), x));
        Map<UUID, List<WorkSpaceResponse.Ref>> m = new HashMap<>();
        for (Habilitation h : hs) {
            AccessGroup x = g.get(h.getGroupeGedId());
            if (x == null || x.isSupprime()) continue;
            List<WorkSpaceResponse.Ref> l = m.computeIfAbsent(h.getNoeudId(), k -> new ArrayList<>());
            if (l.stream().noneMatch(r -> r.id().equals(x.getId()))) {
                l.add(new WorkSpaceResponse.Ref(x.getId(), x.getName()));
            }
        }
        return m;
    }

    /** Petit builder mutable pour assembler l'arbre avant de figer les records. */
    private static final class TreeNodeBuilder {
        final UUID id;
        final String name;
        final String status;
        final UUID parentId;
        final boolean passage;
        final List<TreeNodeBuilder> children = new ArrayList<>();

        TreeNodeBuilder(UUID id, String name, String status, UUID parentId, boolean passage) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.parentId = parentId;
            this.passage = passage;
        }

        TreeNode build() {
            return new TreeNode(id, name, status, parentId, passage,
                    children.stream().map(TreeNodeBuilder::build).toList());
        }
    }
}
