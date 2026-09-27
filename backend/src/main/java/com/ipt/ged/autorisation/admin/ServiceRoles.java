package com.ipt.ged.autorisation.admin;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.autorisation.Permission;
import com.ipt.ged.autorisation.PermissionRepository;
import com.ipt.ged.autorisation.VersionHabilitations;
import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.RoleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rôles composés depuis l'interface (§12.2.1) : « ensembles nommés de
 * permissions, créés et composés sans développement ».
 *
 * <p>Toute modification de composition est effective immédiatement pour tous
 * les porteurs du rôle (compteur {@code version_habilitations}) et auditée
 * avant / après. Deux garde-fous :
 * <ul>
 *   <li>le rôle Administrateur n'est pas recomposable : lui retirer
 *       {@code GERER_ROLES_HABILITATIONS} rendrait l'administration des droits
 *       impossible à rétablir sans intervention en base ;</li>
 *   <li>un rôle système ou encore attribué ne se supprime pas.</li>
 * </ul>
 */
@Service
public class ServiceRoles {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");

    /** Rôle tel que l'écran d'administration l'affiche. */
    public record RoleVue(UUID id, String code, String libelle, boolean systeme, boolean accesGlobal,
                          List<String> permissions, boolean attribue) {}

    /** Permission livrée. */
    public record PermissionVue(String code, String libelle, String categorie) {}

    /** Création ou recomposition d'un rôle. */
    public record DemandeRole(String code, String libelle, List<String> permissions) {}

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final HabilitationRepository habilitations;
    private final VersionHabilitations version;
    private final ApplicationEventPublisher evenements;

    public ServiceRoles(RoleRepository roles, PermissionRepository permissions, HabilitationRepository habilitations,
                        VersionHabilitations version, ApplicationEventPublisher evenements) {
        this.roles = roles;
        this.permissions = permissions;
        this.habilitations = habilitations;
        this.version = version;
        this.evenements = evenements;
    }

    @Transactional(readOnly = true)
    public List<PermissionVue> permissions() {
        return permissions.findAllByOrderByCategorieAscCodeAsc().stream()
                .map(p -> new PermissionVue(p.getCode(), p.getLibelle(), p.getCategorie())).toList();
    }

    @Transactional(readOnly = true)
    public List<RoleVue> lister() {
        return roles.findAll().stream()
                .sorted(Comparator.comparing((Role r) -> !r.isSysteme()).thenComparing(Role::getCode))
                .map(this::vue).toList();
    }

    @Transactional
    public RoleVue creer(DemandeRole d) {
        String code = d.code() == null ? "" : d.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Code de rôle invalide : majuscules, chiffres et soulignés (2 à 50)");
        }
        String libelle = libelle(d.libelle());
        if (roles.findByCode(code).isPresent()) {
            throw new DuplicateKeyException("Le code de rôle « " + code + " » est déjà utilisé");
        }
        Role r = new Role(code, libelle);
        r.getPermissions().addAll(resoudre(d.permissions()));
        roles.save(r);
        version.incrementer();
        publier(HabilitationModifiee.AJOUT, r.getId(), null, instantane(r));
        return vue(r);
    }

    @Transactional
    public RoleVue modifier(UUID id, DemandeRole d) {
        Role r = roles.findById(id).orElseThrow(() -> new EntityNotFoundException("Rôle introuvable : " + id));
        Map<String, Object> avant = instantane(r);
        if (Role.ADMINISTRATEUR.equals(r.getCode()) && d.permissions() != null
                && !new TreeSet<>(d.permissions()).equals(new TreeSet<>(codes(r)))) {
            throw new IllegalArgumentException("La composition du rôle Administrateur n'est pas modifiable");
        }
        if (d.libelle() != null) r.setLibelle(libelle(d.libelle()));
        if (d.permissions() != null) {
            r.getPermissions().clear();
            r.getPermissions().addAll(resoudre(d.permissions()));
        }
        roles.save(r);
        version.incrementer();
        publier(HabilitationModifiee.MODIFICATION, r.getId(), avant, instantane(r));
        return vue(r);
    }

    @Transactional
    public void supprimer(UUID id) {
        Role r = roles.findById(id).orElseThrow(() -> new EntityNotFoundException("Rôle introuvable : " + id));
        if (r.isSysteme()) {
            throw new IllegalArgumentException("Un rôle système ne se supprime pas");
        }
        if (habilitations.existsByRoleId(id)) {
            throw new DuplicateKeyException("Rôle encore attribué : retirez d'abord ses habilitations");
        }
        Map<String, Object> avant = instantane(r);
        roles.delete(r);
        version.incrementer();
        publier(HabilitationModifiee.RETRAIT, id, avant, null);
    }

    /* ---------------------------------------------------------------- outils */

    private List<Permission> resoudre(List<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        Set<String> demandes = new TreeSet<>();
        for (String c : codes) {
            if (c == null || CodePermission.depuis(c.trim()) == null) {
                throw new IllegalArgumentException("Permission inconnue : " + c);
            }
            demandes.add(c.trim());
        }
        List<Permission> l = permissions.findByCodeIn(demandes);
        if (l.size() != demandes.size()) {
            throw new IllegalArgumentException("Permission absente du référentiel");
        }
        return l;
    }

    private static String libelle(String l) {
        if (l == null || l.isBlank()) throw new IllegalArgumentException("Le libellé du rôle est obligatoire");
        if (l.trim().length() > 255) throw new IllegalArgumentException("Libellé limité à 255 caractères");
        return l.trim();
    }

    private static List<String> codes(Role r) {
        List<String> l = new ArrayList<>();
        r.getPermissions().forEach(p -> l.add(p.getCode()));
        l.sort(null);
        return l;
    }

    private RoleVue vue(Role r) {
        return new RoleVue(r.getId(), r.getCode(), r.getLibelle(), r.isSysteme(), r.isAccesGlobal(), codes(r),
                habilitations.existsByRoleId(r.getId()));
    }

    private static Map<String, Object> instantane(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("libelle", r.getLibelle());
        m.put("permissions", codes(r));
        return m;
    }

    private void publier(String operation, UUID id, Map<String, Object> avant, Map<String, Object> apres) {
        evenements.publishEvent(new HabilitationModifiee(HabilitationModifiee.ROLE, operation, id, avant, apres,
                ActeurCourant.utilisateurId(), Instant.now()));
    }
}
