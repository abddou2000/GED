package com.ipt.ged.workflow.circuit;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.workflow.api.AccesApiWorkflow;
import com.ipt.ged.workflow.api.ActeurWorkflow;
import com.ipt.ged.workflow.api.OperationWorkflow;
import com.ipt.ged.workflow.circuit.dto.CircuitResponse;
import com.ipt.ged.workflow.circuit.dto.VuesWorkflow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * API du workflow de validation (§12.8), contrat E8-API publié pour dev2 (D8) :
 * les mêmes points d'entrée servent l'interface et l'intranet. Chaque appel
 * résout d'abord l'acteur (l'utilisateur, ou la personne déléguée par une
 * application) et la portée de la clé ({@link AccesApiWorkflow}) ; les droits
 * restent ceux d'{@code AccessPredicate}, vérifiés par le service.
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class CircuitController {

    private final ServiceCircuits service;
    private final RattachementRegles rattachements;
    private final AccesApiWorkflow acces;

    public CircuitController(ServiceCircuits service, RattachementRegles rattachements, AccesApiWorkflow acces) {
        this.service = service;
        this.rattachements = rattachements;
        this.acces = acces;
    }

    /* ---------------- rattachement des règles */

    @PutMapping("/noeuds/{noeudId}/regle")
    public ResponseEntity<Void> regleDuNoeud(@PathVariable UUID noeudId,
                                             @RequestBody VuesWorkflow.DemandeRattachement corps,
                                             Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, noeudId);
        rattachements.rattacherNoeud(noeudId, corps.regleId(), a);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/types/{typeId}/regle")
    public ResponseEntity<Void> regleDuType(@PathVariable UUID typeId,
                                            @RequestBody VuesWorkflow.DemandeRattachement corps,
                                            Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, null);
        rattachements.rattacherType(typeId, corps.regleId(), a);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{documentId}/regle")
    public ResponseEntity<VuesWorkflow.RegleDocument> regleDuDocument(@PathVariable UUID documentId,
                                                                     Authentication auth, HttpServletRequest requete) {
        return service.regleDuDocument(documentId, acces.acteur(auth, requete))
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /* ---------------- circuits */

    @GetMapping("/documents/{documentId}/circuits")
    public List<CircuitResponse> circuits(@PathVariable UUID documentId, Authentication auth,
                                          HttpServletRequest requete) {
        return service.circuitsDuDocument(documentId, acces.acteur(auth, requete));
    }

    @GetMapping("/circuits/{circuitId}")
    public CircuitResponse circuit(@PathVariable UUID circuitId, Authentication auth, HttpServletRequest requete) {
        return service.circuit(circuitId, acces.acteur(auth, requete));
    }

    @PostMapping("/documents/{documentId}/circuits")
    public ResponseEntity<CircuitResponse> ouvrir(@PathVariable UUID documentId, Authentication auth,
                                                  HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, service.noeudDuDocument(documentId));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.ouvrir(documentId, a));
    }

    @PostMapping("/circuits/{circuitId}/decisions")
    public ResponseEntity<CircuitResponse> decider(@PathVariable UUID circuitId,
                                                   @Valid @RequestBody VuesWorkflow.DemandeDecision corps,
                                                   Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = acces.acteur(auth, requete);
        acces.verifierPortee(auth, OperationWorkflow.DECISION, service.noeudDuCircuit(circuitId));
        return ResponseEntity.status(HttpStatus.CREATED).body(service.decider(circuitId, corps, a));
    }

    @PostMapping("/circuits/{circuitId}/annulation")
    public CircuitResponse annuler(@PathVariable UUID circuitId, @RequestBody VuesWorkflow.DemandeMotif corps,
                                   Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, service.noeudDuCircuit(circuitId));
        return service.annuler(circuitId, corps.motif(), a);
    }

    @PutMapping("/circuits/{circuitId}/validateurs/{validateurId}")
    public CircuitResponse reaffecter(@PathVariable UUID circuitId, @PathVariable UUID validateurId,
                                      @Valid @RequestBody VuesWorkflow.DemandeReaffectation corps,
                                      Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, service.noeudDuCircuit(circuitId));
        return service.reaffecter(circuitId, validateurId, corps, a);
    }

    /* ---------------- listes de l'acteur */

    @GetMapping("/a-traiter")
    public PageResponse<VuesWorkflow.ATraiter> aTraiter(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        Authentication auth, HttpServletRequest requete) {
        List<VuesWorkflow.ATraiter> tout = service.aTraiter(acces.acteur(auth, requete));
        int taille = Math.max(1, Math.min(size, 100));
        int p = Math.max(0, page);
        int debut = Math.min(p * taille, tout.size());
        int fin = Math.min(debut + taille, tout.size());
        return new PageResponse<>(tout.subList(debut, fin), tout.size(), p, taille,
                (tout.size() + taille - 1) / taille);
    }

    @GetMapping("/historique")
    public List<VuesWorkflow.DecisionRendue> historique(Authentication auth, HttpServletRequest requete) {
        return service.historique(acces.acteur(auth, requete));
    }

    @GetMapping("/anomalies")
    public List<VuesWorkflow.Anomalie> anomalies(Authentication auth, HttpServletRequest requete) {
        return service.anomalies(acces.acteur(auth, requete));
    }

    /* ---------------- diffusion */

    @PostMapping("/documents/{documentId}/diffusion")
    public VuesWorkflow.ResultatDiffusion diffuser(@PathVariable UUID documentId,
                                                   @RequestBody VuesWorkflow.DemandeDiffusion corps,
                                                   Authentication auth, HttpServletRequest requete) {
        ActeurWorkflow a = pilotage(auth, requete, service.noeudDuDocument(documentId));
        return service.diffuser(documentId, corps, a);
    }

    private ActeurWorkflow pilotage(Authentication auth, HttpServletRequest requete, UUID noeudId) {
        ActeurWorkflow a = acces.acteur(auth, requete);
        acces.verifierPortee(auth, OperationWorkflow.PILOTAGE, noeudId);
        return a;
    }
}
