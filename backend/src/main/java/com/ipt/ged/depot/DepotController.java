package com.ipt.ged.depot;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Dépôt d'un document, avec ses métadonnées en une seule opération (§5.3.1 :
 * {@code POST /documents}, multipart fichier + métadonnées JSON).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DepotController {

    private final DepotService depot;

    public DepotController(DepotService depot) {
        this.depot = depot;
    }

    /**
     * Dépose un document.
     *
     * <p>Parties : {@code file} (obligatoire) et {@code metadonnees} (facultative,
     * objet JSON des index du plan, 64 Ko au plus). Réponse : <b>202</b> quand le
     * contenu part à l'OCR ({@code statutOcr = EN_ATTENTE_OCR}), <b>201</b>
     * sinon ; le corps porte l'identifiant du document et l'issue de
     * l'indexation ({@code statutIndexation} : {@code INDEXE}, {@code SANS_PLAN}
     * ou {@code A_INDEXER}, avec {@code motifIndexation} en cas d'échec du
     * temps 2).
     *
     * <p>Le déposant est l'utilisateur authentifié, jamais un paramètre de la
     * requête ; un {@code createdById} résiduel est ignoré.
     *
     * <p>{@code noeudId} (facultatif) : dossier où ranger le document. Absent,
     * le document va dans le dossier de son type. Un autre dossier n'est admis
     * que dans le même espace d'échange que celui du type (D12) ; Déposer y est
     * exigé ; ailleurs, 422 {@code EMPLACEMENT_HORS_ESPACE_ECHANGE}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> deposer(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "metadonnees", required = false) String metadonnees,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam("typeDocumentId") UUID typeDocumentId,
            @RequestParam(value = "expirationDate", required = false) String expirationDate,
            @RequestParam(value = "etiquetteIds", required = false) List<UUID> etiquetteIds,
            @RequestParam(value = "confidentialite", required = false) Confidentialite confidentialite,
            @RequestParam(value = "objet", required = false) String objet,
            @RequestParam(value = "dateDocument", required = false) String dateDocument,
            @RequestParam(value = "noeudId", required = false) UUID noeudId,
            @AuthenticationPrincipal UtilisateurConnecte principal) {
        UUID deposant = principal != null ? principal.getEmployeId() : null;
        DepotService.ResultatDepot r = depot.deposer(file, name, typeDocumentId, expirationDate, deposant,
                etiquetteIds, confidentialite, metadonnees, objet, dateDocument, noeudId);
        return ResponseEntity.status(r.ocrEnAttente() ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(r.document());
    }
}
