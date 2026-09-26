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

    public EmployeController(EmployeRepository repository, ProfilService profils) {
        this.repository = repository;
        this.profils = profils;
    }

    /** GET /api/v1/employes?has_user=1  → liste (filtrée sur les employés avec compte si has_user=1). */
    @GetMapping
    public List<EmployeResponse> list(@RequestParam(name = "has_user", required = false) Integer hasUser) {
        List<Employe> employes = (hasUser != null && hasUser == 1)
                ? repository.findByHasUserTrue()
                : repository.findAll();
        return employes.stream().map(EmployeResponse::from).toList();
    }

    /**
     * GET /api/v1/employes/{id}/profil → fiche de profil (identité, activité).
     *
     * <p>Ouverte à tout appelant authentifié : l'application n'a qu'un seul
     * utilisateur, il n'y a plus de « fiche d'autrui » à protéger d'un pair.
     */
    @GetMapping("/{id}/profil")
    public ProfilResponse profil(@PathVariable UUID id) {
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

    /** DTO de sortie : ce que le frontend affiche dans les sélecteurs. */
    public record EmployeResponse(UUID id, String firstName, String lastName, String fullName) {
        static EmployeResponse from(Employe e) {
            return new EmployeResponse(e.getId(), e.getFirstName(), e.getLastName(), e.getFullName());
        }
    }
}
