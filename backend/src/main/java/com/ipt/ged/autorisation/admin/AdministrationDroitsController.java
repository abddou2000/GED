package com.ipt.ged.autorisation.admin;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.admin.dto.DemandeHabilitation;
import com.ipt.ged.autorisation.admin.dto.HabilitationVue;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administration des droits (§12.2) : permissions, rôles, habilitations et
 * droits effectifs. Base {@code /api/v1/admin} : la chaîne de sécurité la
 * réserve au rôle Administrateur (portée globale) ; chaque point d'entrée
 * exige en outre la permission {@code GERER_ROLES_HABILITATIONS}, pour qu'un
 * Administrateur dont le rôle serait recomposé perde aussi cet accès.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdministrationDroitsController {

    private final ServiceRoles roles;
    private final ServiceHabilitations habilitations;
    private final ServiceDroitsEffectifs droitsEffectifs;
    private final ControleAcces controle;

    public AdministrationDroitsController(ServiceRoles roles, ServiceHabilitations habilitations,
                                          ServiceDroitsEffectifs droitsEffectifs, ControleAcces controle) {
        this.roles = roles;
        this.habilitations = habilitations;
        this.droitsEffectifs = droitsEffectifs;
        this.controle = controle;
    }

    private void exiger() {
        controle.exigerAdministration(CodePermission.GERER_ROLES_HABILITATIONS);
    }

    @GetMapping("/permissions")
    public List<ServiceRoles.PermissionVue> permissions() {
        exiger();
        return roles.permissions();
    }

    @GetMapping("/roles")
    public List<ServiceRoles.RoleVue> roles() {
        exiger();
        return roles.lister();
    }

    @PostMapping("/roles")
    public ResponseEntity<ServiceRoles.RoleVue> creerRole(@RequestBody ServiceRoles.DemandeRole demande) {
        exiger();
        return ResponseEntity.status(HttpStatus.CREATED).body(roles.creer(demande));
    }

    @PutMapping("/roles/{id}")
    public ServiceRoles.RoleVue modifierRole(@PathVariable UUID id, @RequestBody ServiceRoles.DemandeRole demande) {
        exiger();
        return roles.modifier(id, demande);
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> supprimerRole(@PathVariable UUID id) {
        exiger();
        roles.supprimer(id);
        return ResponseEntity.noContent().build();
    }

    /** Attributions, filtrées par sujet et / ou cible. */
    @GetMapping("/habilitations")
    public List<HabilitationVue> habilitations(@RequestParam(required = false) TypeSujet sujetType,
                                               @RequestParam(required = false) UUID sujetId,
                                               @RequestParam(required = false) UUID noeudId,
                                               @RequestParam(required = false) UUID documentId) {
        exiger();
        return habilitations.lister(sujetType, sujetId, noeudId, documentId);
    }

    /**
     * Pose une attribution. Donner un premier rôle à une identité provisionnée
     * sans rôle, c'est une habilitation sans cible (portée globale) ou sur un
     * espace.
     */
    @PostMapping("/habilitations")
    public ResponseEntity<HabilitationVue> attribuer(@Valid @RequestBody DemandeHabilitation demande) {
        exiger();
        return ResponseEntity.status(HttpStatus.CREATED).body(habilitations.attribuer(demande));
    }

    @DeleteMapping("/habilitations/{id}")
    public ResponseEntity<Void> retirer(@PathVariable UUID id) {
        exiger();
        habilitations.retirer(id);
        return ResponseEntity.noContent().build();
    }

    /** Rapport de reprise : anciens liens groupe / espace, sans droit associé (point 9, lot E7). */
    @GetMapping("/reprise/liens-groupes")
    public List<ServiceHabilitations.LienRepris> liensRepris() {
        exiger();
        return habilitations.liensRepris();
    }

    /** Droits effectifs d'une identité, globalement, sur un nœud ou sur un document (P-22). */
    @GetMapping("/droits-effectifs")
    public ServiceDroitsEffectifs.DroitsEffectifs droitsEffectifs(@RequestParam UUID utilisateurId,
                                                                  @RequestParam(required = false) UUID noeudId,
                                                                  @RequestParam(required = false) UUID documentId) {
        exiger();
        return droitsEffectifs.calculer(utilisateurId, noeudId, documentId);
    }
}
