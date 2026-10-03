package com.ipt.ged.accessgroup;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.JournalAdministration;
import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.autorisation.admin.ServiceHabilitations;
import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.accessgroup.dto.AccessGroupRequest;
import com.ipt.ged.accessgroup.dto.AccessGroupResponse;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.common.erreur.RegleMetierException;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
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
    private final UtilisateurRepository utilisateurRepo;
    private final AccessGroupTriParTaille triParTaille;
    private final ServiceHabilitations habilitations;
    private final VersionHabilitations version;
    private final ApplicationEventPublisher evenements;
    /** Journal d'audit : administration des groupes (DAT §7.4.1), en plus de HABILITATION_MODIFIEE. */
    private final JournalAdministration journal;

    public AccessGroupService(AccessGroupRepository repo, WorkSpaceRepository workspaceRepo,
                              EmployeRepository employeRepo, UtilisateurRepository utilisateurRepo,
                              AccessGroupTriParTaille triParTaille,
                              ServiceHabilitations habilitations, VersionHabilitations version,
                              ApplicationEventPublisher evenements, JournalAdministration journal) {
        this.repo = repo;
        this.workspaceRepo = workspaceRepo;
        this.employeRepo = employeRepo;
        this.utilisateurRepo = utilisateurRepo;
        this.triParTaille = triParTaille;
        this.habilitations = habilitations;
        this.version = version;
        this.evenements = evenements;
        this.journal = journal;
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
        exigerReferencesConnues(req);
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        if (repo.existsByNameIgnoreCase(req.name())) {
            throw new IllegalArgumentException("Le nom « " + req.name() + " » est déjà utilisé");
        }
        AccessGroup g = repo.save(new AccessGroup(req.code(), req.name()));
        apply(g, req);
        AccessGroupResponse cree = reponse(g);
        journal.cree(ActionAudit.GROUPE_CREE, "GROUPE", cree.id(), cree);
        return cree;
    }

    @Transactional
    public AccessGroupResponse update(UUID id, AccessGroupRequest req) {
        AccessGroup g = load(id);
        exigerReferencesConnues(req);
        AccessGroupResponse avant = reponse(g);
        if (repo.existsByCodeIgnoreCaseAndIdNot(req.code(), id)) {
            throw new IllegalArgumentException("Le code « " + req.code() + " » est déjà utilisé");
        }
        if (repo.existsByNameIgnoreCaseAndIdNot(req.name(), id)) {
            throw new IllegalArgumentException("Le nom « " + req.name() + " » est déjà utilisé");
        }
        g.setCode(req.code());
        g.setName(req.name());
        apply(g, req);
        AccessGroupResponse apres = reponse(repo.save(g));
        journal.modifie(ActionAudit.GROUPE_MODIFIE, "GROUPE", id, avant, apres);
        return apres;
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
            journal.action(supprimer ? ActionAudit.GROUPE_SUPPRIME : ActionAudit.GROUPE_RESTAURE, "GROUPE", g.getId());
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

    /**
     * ANO-F-009 : un membre ou un espace inconnu était ignoré en silence — groupe
     * créé en 2xx avec moins de membres que demandé, et un Administrateur (ou une
     * intégration) croyait avoir accordé des droits qui n'existent pas. Refus 422
     * avec la liste des identifiants inconnus, AVANT toute écriture.
     */
    private void exigerReferencesConnues(AccessGroupRequest req) {
        List<UUID> membres = resoudre(req.userIds()).inconnus();
        if (!membres.isEmpty()) {
            throw new RegleMetierException(CodesErreurGroupe.MEMBRES_INCONNUS,
                    "Membre(s) inconnu(s) : " + membres.size() + " identifiant(s) ne désignent ni une fiche employé"
                            + " ni une identité GED. Aucune modification enregistrée.")
                    .avec("identifiantsInconnus", membres);
        }
        List<UUID> espaces = inconnus(req.workspaceIds(), ids -> workspaceRepo.findAllById(ids).stream()
                .map(WorkSpace::getId).collect(Collectors.toSet()));
        if (!espaces.isEmpty()) {
            throw new RegleMetierException(CodesErreurGroupe.ESPACES_INCONNUS,
                    "Espace(s) inconnu(s) : " + espaces.size() + " identifiant(s) ne désignent aucun espace."
                            + " Aucune modification enregistrée.")
                    .avec("identifiantsInconnus", espaces);
        }
    }

    /** Identifiants demandés absents de la base, dans l'ordre de la requête, sans doublon. */
    private static List<UUID> inconnus(List<UUID> demandes, Function<Set<UUID>, Set<UUID>> existants) {
        if (demandes == null || demandes.isEmpty()) return List.of();
        Set<UUID> uniques = new LinkedHashSet<>(demandes);
        uniques.remove(null);
        Set<UUID> trouves = existants.apply(uniques);
        return uniques.stream().filter(id -> !trouves.contains(id)).toList();
    }

    /**
     * Membres demandés, traduits pour {@code groupe_membre} (T-025, écart 2) :
     * {@code userIds} accepte l'identifiant d'une fiche employé (contrat
     * d'origine, celui de l'écran) ou celui d'une identité GED. Une fiche qui a
     * une identité devient l'appartenance de cette identité ; une fiche sans
     * identité (personne jamais connectée) une appartenance en attente.
     */
    private Membres resoudre(List<UUID> demandes) {
        if (demandes == null || demandes.isEmpty()) return new Membres(List.of(), List.of(), List.of());
        Set<UUID> uniques = new LinkedHashSet<>(demandes);
        uniques.remove(null);
        Map<UUID, Employe> employes = employeRepo.findAllById(uniques).stream()
                .collect(Collectors.toMap(Employe::getId, Function.identity()));
        Map<UUID, Utilisateur> identitesDesFiches = employes.isEmpty() ? Map.of()
                : utilisateurRepo.findByEmployeIdIn(employes.keySet()).stream()
                        .collect(Collectors.toMap(u -> u.getEmploye().getId(), Function.identity()));
        Set<UUID> autres = uniques.stream().filter(id -> !employes.containsKey(id)).collect(Collectors.toSet());
        Map<UUID, Utilisateur> identites = autres.isEmpty() ? Map.of()
                : utilisateurRepo.findAllById(autres).stream()
                        .collect(Collectors.toMap(Utilisateur::getId, Function.identity()));
        Map<UUID, Utilisateur> membres = new LinkedHashMap<>();
        List<Employe> enAttente = new ArrayList<>();
        List<UUID> inconnus = new ArrayList<>();
        for (UUID id : uniques) {
            Employe e = employes.get(id);
            Utilisateur u = e != null ? identitesDesFiches.get(id) : identites.get(id);
            if (u != null) membres.putIfAbsent(u.getId(), u);
            else if (e != null) enAttente.add(e);
            else inconnus.add(id);
        }
        return new Membres(List.copyOf(membres.values()), enAttente, inconnus);
    }

    private record Membres(List<Utilisateur> identites, List<Employe> enAttente, List<UUID> inconnus) {}

    private void apply(AccessGroup g, AccessGroupRequest req) {
        Map<String, Object> avant = instantane(g);
        // Membres : identités GED, et appartenances en attente de la première connexion.
        Membres membres = resoudre(req.userIds());
        g.getMembres().clear();
        g.getMembres().addAll(membres.identites());
        g.getMembresEnAttente().clear();
        g.getMembresEnAttente().addAll(membres.enAttente());
        repo.save(g);
        Map<String, Object> apres = instantane(g);
        if (!avant.get("membres").equals(apres.get("membres"))) {
            version.incrementer();
        }
        if (!avant.get("membres").equals(apres.get("membres"))
                || !avant.get("membresEnAttente").equals(apres.get("membresEnAttente"))) {
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
        m.put("membres", g.getMembres().stream().map(u -> u.getId().toString()).sorted().toList());
        m.put("membresEnAttente", g.getMembresEnAttente().stream().map(e -> e.getId().toString()).sorted().toList());
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
