package com.ipt.ged.contratapi;

import com.ipt.ged.autorisation.admin.ServiceDroitsEffectifs;
import com.ipt.ged.contratapi.dto.DtoContratApi.DossierRequest;
import com.ipt.ged.contratapi.dto.DtoContratApi.RechercheContratRequest;
import com.ipt.ged.document.DocumentController;
import com.ipt.ged.document.DocumentService;
import com.ipt.ged.recherche.PageResultats;
import com.ipt.ged.workspace.dto.WorkSpaceResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

/**
 * Chemins exacts du contrat d'API (DAT §5.3.1) qui manquaient :
 *
 * <table>
 *   <tr><td>{@code POST /noeuds/{id}/dossiers}</td><td>création de dossier (Idempotency-Key)</td></tr>
 *   <tr><td>{@code POST /recherches}</td><td>recherche multicritère et plein texte, paginée</td></tr>
 *   <tr><td>{@code GET /documents/{id}/contenu?version=}</td><td>téléchargement</td></tr>
 *   <tr><td>{@code GET /documents/{id}/droits}, {@code /noeuds/{id}/droits}</td><td>consultation des droits</td></tr>
 * </table>
 *
 * Les autres opérations du contrat existaient déjà au chemin exact :
 * {@code POST /documents} (dépôt en deux temps, {@code DepotController}),
 * {@code POST /documents/{id}/versions}, {@code POST /documents/{id}/rattachements}
 * et {@code DELETE …/{noeudId}} ({@code DocumentController}). Les anciens chemins
 * du front ({@code /workspaces}, {@code /indexation/recherche},
 * {@code /recherche/plein-texte}, {@code /documents/{id}/download},
 * {@code /admin/droits-effectifs}) restent disponibles.
 *
 * <p>Sécurité : clé d'API ou jeton ; portée de clé et délégation
 * ({@code X-On-Behalf-Of}) s'appliquent par le point d'application unique ; audit
 * par les services délégués (dépôt, téléchargement, création d'espace).
 */
@RestController
public class ContratApiController {

    private final ServiceContratApi contrat;
    private final DocumentService documents;

    public ContratApiController(ServiceContratApi contrat, DocumentService documents) {
        this.contrat = contrat;
        this.documents = documents;
    }

    /** Création de dossier : Déposer sur l'espace ou le dossier parent ; Idempotency-Key obligatoire. */
    @PostMapping("/api/v1/noeuds/{id}/dossiers")
    public ResponseEntity<WorkSpaceResponse> creerDossier(@PathVariable UUID id, @Valid @RequestBody DossierRequest demande) {
        WorkSpaceResponse cree = contrat.creerDossier(id, demande);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/workspaces/{id}").buildAndExpand(cree.id()).toUri())
                .body(cree);
    }

    /** Recherche : Consulter, filtrage à la source ; total calculé sur le seul périmètre autorisé. */
    @PostMapping("/api/v1/recherches")
    public PageResultats rechercher(@Valid @RequestBody RechercheContratRequest requete, Authentication appelant) {
        return contrat.rechercher(requete, appelant);
    }

    /** Téléchargement : Consulter + niveau de confidentialité ; version courante par défaut. */
    @GetMapping("/api/v1/documents/{id}/contenu")
    public ResponseEntity<Resource> contenu(@PathVariable UUID id, @RequestParam(required = false) UUID version) {
        return DocumentController.servir(documents.telechargerVersion(id, version));
    }

    /** Droits effectifs sur un document ; {@code pourUtilisateur} (identifiant ou UUID) pour un tiers. */
    @GetMapping("/api/v1/documents/{id}/droits")
    public ServiceDroitsEffectifs.DroitsEffectifs droitsDocument(@PathVariable UUID id,
            @RequestParam(required = false) String pourUtilisateur, Authentication appelant) {
        return contrat.droits(null, id, pourUtilisateur, appelant);
    }

    /** Droits effectifs sur un nœud (espace ou dossier). */
    @GetMapping("/api/v1/noeuds/{id}/droits")
    public ServiceDroitsEffectifs.DroitsEffectifs droitsNoeud(@PathVariable UUID id,
            @RequestParam(required = false) String pourUtilisateur, Authentication appelant) {
        return contrat.droits(id, null, pourUtilisateur, appelant);
    }
}
