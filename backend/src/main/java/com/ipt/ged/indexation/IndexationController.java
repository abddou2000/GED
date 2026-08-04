package com.ipt.ged.indexation;

import com.ipt.ged.indexation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Indexation & recherche par index.
 *
 * <ul>
 *   <li>{@code GET  /criteres}            — critères de recherche dérivés des index</li>
 *   <li>{@code GET  /groupages}           — index utilisables pour regrouper</li>
 *   <li>{@code GET  /documents/{id}/champs} — champs à renseigner (plan du type)</li>
 *   <li>{@code GET  /documents/{id}}      — valeurs actuelles du document</li>
 *   <li>{@code PUT  /documents/{id}}      — enregistre les valeurs</li>
 *   <li>{@code POST /recherche}           — recherche multi-critères</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/indexation")
@RequiredArgsConstructor
public class IndexationController {

    private final IndexationService service;

    @GetMapping("/criteres")
    public List<CritereResponse> criteres() {
        return service.criteres();
    }

    @GetMapping("/groupages")
    public List<CritereResponse> groupages() {
        return service.groupages();
    }

    @GetMapping("/documents/{id}/champs")
    public List<CritereResponse> champs(@PathVariable Long id) {
        return service.champsDuDocument(id);
    }

    /**
     * Propose les valeurs d'index déduites du nom de fichier. Lecture seule :
     * rien n'est enregistré tant que l'opérateur n'a pas confirmé via le PUT.
     */
    @GetMapping("/documents/{id}/analyse")
    public AnalyseResponse analyser(@PathVariable Long id) {
        return service.analyser(id);
    }

    @GetMapping("/documents/{id}")
    public List<ResultatResponse.ValeurResponse> valeurs(@PathVariable Long id) {
        return service.valeurs(id);
    }

    @PutMapping("/documents/{id}")
    public List<ResultatResponse.ValeurResponse> enregistrer(@PathVariable Long id,
                                                             @Valid @RequestBody ValeurRequest requete) {
        return service.enregistrer(id, requete);
    }

    @PostMapping("/recherche")
    public List<GroupeResponse> rechercher(@RequestBody RechercheRequest requete) {
        return service.rechercher(requete);
    }
}
