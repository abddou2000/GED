package com.ipt.ged.modules;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Modules métier de l'environnement (T-088) : le front masque les menus d'un
 * module inactif, la recette UAT vérifie ce qui est déployé. Lecture seule,
 * pour tout appelant authentifié ; l'état se change par configuration.
 */
@RestController
@RequestMapping("/api/v1/modules")
public class ModulesController {

    /** Un module métier et son état sur cet environnement. */
    public record ModuleResponse(String code, String libelle, String reference, boolean actif) {}

    private final ModulesActifs modules;

    public ModulesController(ModulesActifs modules) {
        this.modules = modules;
    }

    @GetMapping
    public List<ModuleResponse> lister() {
        return Arrays.stream(ModuleMetier.values())
                .map(m -> new ModuleResponse(m.code(), m.libelle(), m.reference(), modules.actif(m)))
                .toList();
    }
}
