package com.ipt.ged.accessgroup;

import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.autorisation.admin.ServiceHabilitations;
import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.accessgroup.dto.AccessGroupRequest;
import com.ipt.ged.accessgroup.dto.AccessGroupResponse;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Logique métier des groupes GED (§12.2.1) : CRUD, corbeille, membres et
 * espaces couverts. Depuis le lot E3 un groupe est un SUJET d'habilitations :
 * changer ses membres, le mettre en corbeille ou changer ses espaces modifie
 * les droits de ses membres — chaque écriture incrémente donc
 * {@code version_habilitations} (effet immédiat) et publie
 * {@link HabilitationModifiee} pour l'audit.
 */
@Service
public class AccessGroupService {

    /** Colonnes sur lesquelles le tri est accepté ; toute autre valeur est ignorée. */
    private static final Set<String> TRIS = Set.of("id", "code", "name");

    private final AccessGroupRepository repo;
    private final WorkSpaceRepository workspaceRepo;
    private final EmployeRepository employeRepo;
    private final AccessGroupTriParTaille triParTaille;
    private final ServiceHabilitations habilitations;
    private final VersionHabilitations version;
    private final ApplicationEventPublisher evenements;

    public AccessGroupService(AccessGroupRepository repo, WorkSpaceRepository workspaceRepo,
                              EmployeRepository employeRepo, AccessGroupTriParTaille triParTaille,
                              ServiceHabilitations habilitations, VersionHabilitations version,
                              ApplicationEventPublisher evenements) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.employeRepo = employeRepo;
        this.triParTaille = triParTaille;
        this.habilitations = habilitations;
        this.version = version;
        this.evenements = evenements;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> list(int page, int size, String search,
                                                  String sortBy, String sortDir) {
        return reponses(chercher(page, size, search, sortBy, sortDir, false));
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessGroupResponse> trashed(int page, int size, String search,
                                                     String sortBy, String sortDir) {
        return reponses(chercher(page, size, search, sortBy, sortDir, true));
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
                ? repo.findBySupprimeTrueAndNameContainingIgnoreCase(search, pageable)
                : repo.findBySupprimeFalseAndNameContainingIgnoreCase(search, pageable);
    }

    @Transactional(readOnly = true)
    public AccessGroupResponse get(UUID id) {
        return reponse(load(id));
    }

    @Transactional
    public AccessGroupResponse create(AccessGroupRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        if (repo.existsByNameIgnoreCase(req.name())) {
            throw new IllegalArgumentException("Le nom « " + req.name() + " » est déjà utilisé");
        }
        AccessGroup g = repo.save(new AccessGroup(req.code(), req.name()));
        apply(g, req);
        return reponse(g);
    }

    @Transactional
    public AccessGroupResponse update(UUID id, AccessGroupRequest req) {
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
        return reponse(repo.save(g));
    }

    @Transactional
    public void softDelete(UUID id) {
        corbeille(List.of(load(id)), true);
    }

    // Restauration = inverse de la mise en corbeille.
    @Transactional
    public void restore(UUID id) {
        corbeille(List.of(load(id)), false);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        corbeille(repo.findByIdInAndSupprimeFalse(ids), true);
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        corbeille(repo.findByIdInAndSupprimeTrue(ids), false);
    }

    /**
     * Un groupe en corbeille n'apporte plus aucun droit à ses membres (ses
     * habilitations sont conservées et reprennent effet à la restauration).
     */
    private void corbeille(List<AccessGroup> groupes, boolean supprimer) {
        for (AccessGroup g : groupes) {
            Map<String, Object> avant = instantane(g);
            if (supprimer) g.mettreEnCorbeille(ActeurCourant.employeId());
            else g.restaurer();
            publier(g, avant);
        }
        if (!groupes.isEmpty()) version.incrementer();
    }

    /** Liste allégée {id, name} pour les sélecteurs. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSelect() {
        return repo.findBySupprimeFalseOrderByIdAsc().stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", g.getId());
                    m.put("name", g.getName());
                    return m;
                })
                .toList();
    }

    /* ---------- privé ---------- */

    private AccessGroup load(UUID id) {
        return repo.findWithRefsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Groupe d'accès introuvable : " + id));
    }

    private void apply(AccessGroup g, AccessGroupRequest req) {
        Map<String, Object> avant = instantane(g);
        // Membres (personnes)
        List<UUID> userIds = req.userIds() != null ? req.userIds() : List.of();
        List<Employe> users = userIds.isEmpty() ? List.of() : employeRepo.findAllById(userIds);
        g.getUsers().clear();
        g.getUsers().addAll(users);
        repo.save(g);
        if (!avant.get("membres").equals(instantane(g).get("membres"))) {
            version.incrementer();
            publier(g, avant);
        }

        // Espaces couverts : habilitations du groupe (rôle Utilisateur
        // standard) ; absent de la requête = inchangé.
        if (req.workspaceIds() != null) {
            List<UUID> existants = workspaceRepo.findAllById(req.workspaceIds()).stream()
                    .map(WorkSpace::getId).toList();
            habilitations.couvrirEspaces(g.getId(), existants);
        }
    }

    private Map<String, Object> instantane(AccessGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", g.getCode());
        m.put("nom", g.getName());
        m.put("supprime", g.isSupprime());
        m.put("membres", g.getUsers().stream().map(e -> e.getId().toString()).sorted().toList());
        return m;
    }

    private void publier(AccessGroup g, Map<String, Object> avant) {
        evenements.publishEvent(new HabilitationModifiee(HabilitationModifiee.GROUPE_GED,
                HabilitationModifiee.MODIFICATION, g.getId(), avant, instantane(g),
                ActeurCourant.utilisateurId(), Instant.now()));
    }

    private AccessGroupResponse reponse(AccessGroup g) {
        return AccessGroupResponse.from(g, espaces(List.of(g.getId())).getOrDefault(g.getId(), List.of()));
    }

    private PageResponse<AccessGroupResponse> reponses(Page<AccessGroup> page) {
        Map<UUID, List<AccessGroupResponse.Ref>> ws = espaces(page.getContent().stream().map(AccessGroup::getId).toList());
        return PageResponse.of(page, g -> AccessGroupResponse.from(g, ws.getOrDefault(g.getId(), List.of())));
    }

    /** Espaces couverts par chaque groupe : nœuds où il porte une habilitation avec rôle. */
    private Map<UUID, List<AccessGroupResponse.Ref>> espaces(Collection<UUID> groupes) {
        Map<UUID, List<UUID>> parGroupe = habilitations.noeudsDesGroupes(groupes);
        List<UUID> tous = parGroupe.values().stream().flatMap(List::stream).distinct().toList();
        Map<UUID, String> noms = workspaceRepo.findAllById(tous).stream()
                .collect(Collectors.toMap(WorkSpace::getId, WorkSpace::getName, (a, b) -> a));
        return parGroupe.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().filter(noms::containsKey)
                        .map(id -> new AccessGroupResponse.Ref(id, noms.get(id))).toList()));
    }
}
