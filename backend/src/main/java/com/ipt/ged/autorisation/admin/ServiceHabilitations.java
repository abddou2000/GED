package com.ipt.ged.autorisation.admin;

import com.ipt.ged.accessgroup.AccessGroup;
import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.autorisation.Habilitation;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.autorisation.admin.dto.DemandeHabilitation;
import com.ipt.ged.autorisation.admin.dto.HabilitationVue;
import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.RoleRepository;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Attributions et retraits d'habilitations (§12.2.1, §12.2.3).
 *
 * <p>Seul chemin d'écriture de la table {@code habilitation} : chaque
 * modification est validée, incrémente {@code version_habilitations} (effet
 * immédiat, y compris pour les sessions ouvertes) et publie
 * {@link HabilitationModifiee} avec les valeurs avant / après.
 */
@Service
public class ServiceHabilitations {

    private final HabilitationRepository habilitations;
    private final RoleRepository roles;
    private final UtilisateurRepository utilisateurs;
    private final AccessGroupRepository groupes;
    private final WorkSpaceRepository noeuds;
    private final UploadDocumentRepository documents;
    private final VersionHabilitations version;
    private final ApplicationEventPublisher evenements;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ServiceHabilitations(HabilitationRepository habilitations, RoleRepository roles,
                                UtilisateurRepository utilisateurs, AccessGroupRepository groupes,
                                WorkSpaceRepository noeuds, UploadDocumentRepository documents,
                                VersionHabilitations version, ApplicationEventPublisher evenements) {
        this.habilitations = habilitations;
        this.roles = roles;
        this.utilisateurs = utilisateurs;
        this.groupes = groupes;
        this.noeuds = noeuds;
        this.documents = documents;
        this.version = version;
        this.evenements = evenements;
    }

    /** Attributions filtrées par sujet et / ou cible (tous critères facultatifs). */
    @Transactional(readOnly = true)
    public List<HabilitationVue> lister(TypeSujet sujetType, UUID sujetId, UUID noeudId, UUID documentId) {
        List<Habilitation> l;
        if (noeudId != null) {
            l = habilitations.findByNoeudId(noeudId);
        } else if (documentId != null) {
            l = habilitations.findByDocumentId(documentId);
        } else if (sujetId != null && sujetType == TypeSujet.GROUPE) {
            l = habilitations.findByGroupeGedId(sujetId);
        } else if (sujetId != null && sujetType == TypeSujet.APPLICATION) {
            l = habilitations.findByApplicationId(sujetId);
        } else if (sujetId != null) {
            l = habilitations.findByUtilisateurId(sujetId);
        } else {
            l = habilitations.findAll();
        }
        if (sujetId != null) {
            l = l.stream().filter(h -> sujetId.equals(h.sujetId())).toList();
        }
        return vues(l.stream().sorted(Comparator.comparing(Habilitation::getCreeLe)).toList());
    }

    @Transactional
    public HabilitationVue attribuer(DemandeHabilitation d) {
        return attribuer(d, ActeurCourant.utilisateurId());
    }

    /**
     * Pose une attribution après validation : sujet et cible existants, rôle
     * présent (sauf rupture seule), rupture uniquement sur un nœud, pas de
     * doublon.
     */
    @Transactional
    public HabilitationVue attribuer(DemandeHabilitation d, UUID auteur) {
        if (d.noeudId() != null && d.documentId() != null) {
            throw new IllegalArgumentException("Une habilitation vise un nœud OU un document, pas les deux");
        }
        if (d.ruptureHeritage() && d.noeudId() == null) {
            throw new IllegalArgumentException("La rupture d'héritage se pose sur un nœud (espace ou dossier)");
        }
        if (d.roleId() == null && !d.ruptureHeritage()) {
            throw new IllegalArgumentException("Le rôle est obligatoire (sauf pour une rupture d'héritage seule)");
        }
        verifierSujet(d.sujetType(), d.sujetId());
        Role role = d.roleId() == null ? null : roles.findById(d.roleId())
                .orElseThrow(() -> new EntityNotFoundException("Rôle introuvable : " + d.roleId()));
        if (d.noeudId() != null && !noeuds.existsById(d.noeudId())) {
            throw new EntityNotFoundException("Nœud introuvable : " + d.noeudId());
        }
        if (d.documentId() != null && !documents.existsById(d.documentId())) {
            throw new EntityNotFoundException("Document introuvable : " + d.documentId());
        }
        if (habilitations.existe(d.sujetType(), d.sujetId(), d.roleId(), d.noeudId(), d.documentId())) {
            throw new com.ipt.ged.autorisation.ConflitAutorisationException("Cette attribution existe déjà");
        }
        Habilitation h = Habilitation.pour(d.sujetType(), d.sujetId());
        h.setRole(role);
        h.setNoeudId(d.noeudId());
        h.setDocumentId(d.documentId());
        h.setRuptureHeritage(d.ruptureHeritage());
        h.setCreePar(auteur);
        h.setCreeLe(Instant.now());
        HabilitationVue vue = vue(habilitations.save(h));
        version.incrementer();
        evenements.publishEvent(new HabilitationModifiee(HabilitationModifiee.HABILITATION,
                HabilitationModifiee.AJOUT, h.getId(), null, vue.instantane(), auteur, Instant.now()));
        return vue;
    }

    /** Retrait immédiat d'une attribution. */
    @Transactional
    public void retirer(UUID habilitationId) {
        Habilitation h = habilitations.findById(habilitationId)
                .orElseThrow(() -> new EntityNotFoundException("Habilitation introuvable : " + habilitationId));
        HabilitationVue avant = vue(h);
        habilitations.delete(h);
        version.incrementer();
        evenements.publishEvent(new HabilitationModifiee(HabilitationModifiee.HABILITATION,
                HabilitationModifiee.RETRAIT, habilitationId, avant.instantane(), null,
                ActeurCourant.utilisateurId(), Instant.now()));
    }

    /**
     * Espaces « couverts » par un groupe (écran des groupes) : chaque nœud
     * coché reçoit une habilitation du groupe avec le rôle Utilisateur
     * standard, chaque nœud décoché perd les habilitations du groupe qui y
     * sont posées. Raccourci de l'écran historique ; le détail des rôles se
     * règle dans l'écran des habilitations.
     */
    @Transactional
    public void couvrirEspaces(UUID groupeId, Collection<UUID> noeudsVoulus) {
        List<Habilitation> actuelles = habilitations.findByGroupeGedIdAndNoeudIdIsNotNull(groupeId);
        java.util.Set<UUID> voulus = new java.util.HashSet<>(noeudsVoulus);
        for (Habilitation h : actuelles) {
            if (!voulus.contains(h.getNoeudId())) retirer(h.getId());
        }
        java.util.Set<UUID> deja = actuelles.stream().map(Habilitation::getNoeudId).collect(Collectors.toSet());
        UUID standard = roles.findByCode(Role.UTILISATEUR_STANDARD).map(Role::getId).orElseThrow();
        for (UUID n : voulus) {
            if (!deja.contains(n)) {
                attribuer(new DemandeHabilitation(TypeSujet.GROUPE, groupeId, standard, n, null, false));
            }
        }
    }

    /** Nœuds sur lesquels un groupe porte au moins une habilitation (fiche du groupe). */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> noeudsDesGroupes(Collection<UUID> groupeIds) {
        if (groupeIds.isEmpty()) return Map.of();
        return habilitations.findByGroupeGedIdInAndNoeudIdIsNotNull(groupeIds).stream()
                .filter(h -> h.getRole() != null)
                .collect(Collectors.groupingBy(Habilitation::getGroupeGedId,
                        Collectors.mapping(Habilitation::getNoeudId, Collectors.collectingAndThen(
                                Collectors.toList(), l -> l.stream().distinct().toList()))));
    }

    /** Ancien lien groupe / espace consigné par la reprise (point 9, lot E7). */
    public record LienRepris(UUID groupeId, String groupe, UUID noeudId, String noeud, Instant reprisLe,
                             boolean habilitationPosee) {}

    /**
     * Rapport de reprise : les espaces que chaque groupe « couvrait » dans
     * l'ancienne application, sans aucun droit associé. L'Administrateur s'en
     * sert pour poser les habilitations voulues ; {@code habilitationPosee}
     * dit si le groupe en porte déjà une sur le nœud.
     */
    @Transactional(readOnly = true)
    public List<LienRepris> liensRepris() {
        return jdbc.query("SELECT r.groupe_ged_id, g.name, r.noeud_id, n.name, r.repris_le,"
                        + " EXISTS (SELECT 1 FROM habilitation h WHERE h.sujet_type = 'GROUPE'"
                        + " AND h.groupe_ged_id = r.groupe_ged_id AND h.noeud_id = r.noeud_id)"
                        + " FROM reprise_lien_groupe_espace r JOIN groupe_ged g ON g.id = r.groupe_ged_id"
                        + " JOIN noeud n ON n.id = r.noeud_id ORDER BY g.name, n.chemin",
                (rs, i) -> new LienRepris(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getBoolean(6)));
    }

    /* ---------------------------------------------------------------- vues */

    private void verifierSujet(TypeSujet type, UUID id) {
        switch (type) {
            case UTILISATEUR -> {
                if (!utilisateurs.existsById(id)) throw new EntityNotFoundException("Identité introuvable : " + id);
            }
            case GROUPE -> {
                AccessGroup g = groupes.findById(id)
                        .orElseThrow(() -> new EntityNotFoundException("Groupe introuvable : " + id));
                if (g.isSupprime()) {
                    throw new IllegalArgumentException("Groupe en corbeille : restaurez-le d'abord");
                }
            }
            // La table des applications (clés d'API) arrive au lot E9 : la clé
            // étrangère sera posée avec elle.
            case APPLICATION -> Objects.requireNonNull(id);
        }
    }

    public HabilitationVue vue(Habilitation h) {
        return vues(List.of(h)).get(0);
    }

    private List<HabilitationVue> vues(List<Habilitation> l) {
        Map<UUID, String> sujets = new HashMap<>();
        List<UUID> idsUtil = l.stream().map(Habilitation::getUtilisateurId).filter(Objects::nonNull).distinct().toList();
        utilisateurs.findAllById(idsUtil).forEach(u -> sujets.put(u.getId(), libelle(u)));
        List<UUID> idsGroupes = l.stream().map(Habilitation::getGroupeGedId).filter(Objects::nonNull).distinct().toList();
        groupes.findAllById(idsGroupes).forEach(g -> sujets.put(g.getId(), g.getName()));
        Map<UUID, String> nomsNoeuds = noeuds.findAllById(
                        l.stream().map(Habilitation::getNoeudId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(WorkSpace::getId, WorkSpace::getName));
        Map<UUID, String> nomsDocs = documents.findAllById(
                        l.stream().map(Habilitation::getDocumentId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(UploadDocument::getId, UploadDocument::getName));
        return l.stream().map(h -> {
            Optional<Role> r = Optional.ofNullable(h.getRole());
            return new HabilitationVue(h.getId(), h.getSujetType(), h.sujetId(),
                    sujets.getOrDefault(h.sujetId(), h.sujetId().toString()),
                    r.map(Role::getId).orElse(null), r.map(Role::getCode).orElse(null),
                    r.map(Role::getLibelle).orElse(null),
                    h.getNoeudId(), nomsNoeuds.get(h.getNoeudId()),
                    h.getDocumentId(), nomsDocs.get(h.getDocumentId()),
                    h.isRuptureHeritage(), h.getCreeLe());
        }).toList();
    }

    private static String libelle(Utilisateur u) {
        return u.getEmploye().getFullName() + " (" + u.getIdentifiant() + ")";
    }
}
