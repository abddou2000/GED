package com.ipt.ged.employe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Expose la liste des employés (utilisée par les menus déroulants du frontend,
 * notamment le choix de l'approbateur dans « Règles de Workflow »).
 */
@RestController
@RequestMapping("/api/v1/employes")
public class EmployeController {

    private final EmployeRepository repository;

    public EmployeController(EmployeRepository repository) {
        this.repository = repository;
    }

    /** GET /api/v1/employes?has_user=1  → liste (filtrée sur les employés avec compte si has_user=1). */
    @GetMapping
    public List<EmployeResponse> list(@RequestParam(name = "has_user", required = false) Integer hasUser) {
        List<Employe> employes = (hasUser != null && hasUser == 1)
                ? repository.findByHasUserTrue()
                : repository.findAll();
        return employes.stream().map(EmployeResponse::from).toList();
    }

    /** DTO de sortie : ce que le frontend affiche dans les sélecteurs. */
    public record EmployeResponse(Long id, String firstName, String lastName, String fullName) {
        static EmployeResponse from(Employe e) {
            return new EmployeResponse(e.getId(), e.getFirstName(), e.getLastName(), e.getFullName());
        }
    }
}
