package com.ipt.ged.employe;

import com.ipt.ged.employe.dto.ProfilResponse;
import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Expose la liste des employés (utilisée par les menus déroulants du frontend,
 * notamment le choix de l'approbateur dans « Règles de Workflow »).
 */
@RestController
@RequestMapping("/api/v1/employes")
public class EmployeController {

    private final EmployeRepository repository;
    private final ProfilService profils;
    private final com.ipt.ged.autorisation.ControleAcces controle;
    private final com.ipt.ged.identite.UtilisateurRepository utilisateurs;

    public EmployeController(EmployeRepository repository, ProfilService profils,
                             com.ipt.ged.autorisation.ControleAcces controle,
                             com.ipt.ged.identite.UtilisateurRepository utilisateurs) {
        this.repository = repository;
        this.profils = profils;
        this.controle = controle;
        this.utilisateurs = utilisateurs;
    }

    /**
     * GET /api/v1/employes?has_user=1  → liste (filtrée sur les employés avec compte si has_user=1).
     * Chaque personne porte son identité GED ({@code utilisateurId}, absente sans
     * compte) : c'est elle que désignent la désignation d'un document
     * confidentiel, le critère « déposant » et l'auteur d'une version
     * (ANO-F-012, ANO-F-013).
     */
    @GetMapping
    public List<EmployeResponse> list(@RequestParam(name = "has_user", required = false) Integer hasUser) {
        List<Employe> employes = (hasUser != null && hasUser == 1)
                ? repository.findByHasUserTrue()
                : repository.findAll();
        java.util.Map<UUID, UUID> identites = new java.util.HashMap<>();
        utilisateurs.findAll().forEach(u -> identites.put(u.getEmploye().getId(), u.getId()));
        return employes.stream().map(e -> EmployeResponse.from(e, identites.get(e.getId()))).toList();
    }

    /**
     * GET /api/v1/employes/{id}/profil → fiche de profil (identité, activité).
     *
     * <p>Depuis le lot E3 (plusieurs utilisateurs), la fiche d'autrui — courriel
     * d'annuaire, groupes, activité — est réservée à qui administre les droits
     * ({@code GERER_ROLES_HABILITATIONS}) ; pour les autres elle est
     * introuvable (404, P5). Sa propre fiche reste accessible.
     */
    @GetMapping("/{id}/profil")
    public ProfilResponse profil(@PathVariable UUID id, @AuthenticationPrincipal UtilisateurConnecte principal) {
        boolean soi = principal != null && id.equals(principal.getEmployeId());
        if (!soi && !controle.administre(com.ipt.ged.autorisation.CodePermission.GERER_ROLES_HABILITATIONS)) {
            throw new com.ipt.ged.autorisation.HorsPerimetreException("Employé introuvable : " + id);
        }
        return profils.profil(id);
    }

    /**
     * GET /api/v1/employes/profil → profil du porteur du jeton.
     *
     * <p>C'est la route à privilégier côté frontend : elle ne transporte aucun
     * identifiant, donc rien à falsifier.
     */
    @GetMapping("/profil")
    public ProfilResponse profilCourant(@AuthenticationPrincipal UtilisateurConnecte principal) {
        return profils.profilCourant(principal);
    }

    /**
     * DTO de sortie : ce que le frontend affiche dans les sélecteurs.
     *
     * @param utilisateurId identité GED de la personne ; {@code null} si elle n'a pas de compte
     */
    public record EmployeResponse(UUID id, String firstName, String lastName, String fullName, UUID utilisateurId) {
        static EmployeResponse from(Employe e, UUID utilisateurId) {
            return new EmployeResponse(e.getId(), e.getFirstName(), e.getLastName(), e.getFullName(), utilisateurId);
        }
    }
}
