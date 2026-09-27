package com.ipt.ged.document;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.document.dto.DocumentRequest;
import com.ipt.ged.document.dto.DocumentResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API REST du dépôt de documents. Base : /api/v1/documents
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<DocumentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return service.list(page, size, search, workspaceId, sortBy, sortDir);
    }

    @GetMapping("/trashed")
    public PageResponse<DocumentResponse> trashed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return service.trashed(page, size, search, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Dépose un document.
     *
     * <p>Le créateur n'est plus un paramètre de la requête mais l'utilisateur
     * AUTHENTIFIÉ. Auparavant le navigateur l'annonçait : n'importe qui pouvait
     * déposer une pièce au nom d'un collègue, et la colonne « Créateur » — qui
     * sert de trace de responsabilité — devenait déclarative. Un paramètre
     * {@code createdById} résiduel est ignoré plutôt que refusé, pour ne pas
     * casser un appel ancien sur un champ qui n'a jamais eu autorité.</p>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam("typeDocumentId") UUID typeDocumentId,
            @RequestParam(value = "expirationDate", required = false) String expirationDate,
            @RequestParam(value = "etiquetteIds", required = false) List<UUID> etiquetteIds,
            @RequestParam(value = "confidentialite", required = false) Confidentialite confidentialite,
            @AuthenticationPrincipal UtilisateurConnecte principal) {
        UUID createdById = principal != null ? principal.getEmployeId() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(file, name, typeDocumentId, expirationDate, createdById, etiquetteIds,
                        confidentialite));
    }

    /** Emplacements complémentaires visibles (§12.4). */
    @GetMapping("/{id}/rattachements")
    public List<DocumentResponse.Ref> rattachements(@PathVariable UUID id) {
        return service.rattachements(id);
    }

    /** Rattache le document à un espace supplémentaire : body { "noeudId": ... }. */
    @PostMapping("/{id}/rattachements")
    public ResponseEntity<DocumentResponse> rattacher(@PathVariable UUID id, @RequestBody Map<String, UUID> body) {
        UUID noeudId = body.get("noeudId");
        if (noeudId == null) throw new IllegalArgumentException("L'espace de rattachement (noeudId) est obligatoire");
        return ResponseEntity.status(HttpStatus.CREATED).body(service.rattacher(id, noeudId));
    }

    /** Retire un rattachement : le document et ses autres emplacements sont intacts. */
    @DeleteMapping("/{id}/rattachements/{noeudId}")
    public ResponseEntity<Void> detacher(@PathVariable UUID id, @PathVariable UUID noeudId) {
        service.detacher(id, noeudId);
        return ResponseEntity.noContent().build();
    }

    /** Personnes désignées d'un document confidentiel (§12.3). */
    @GetMapping("/{id}/designes")
    public List<DocumentResponse.Ref> designes(@PathVariable UUID id) {
        return service.designes(id);
    }

    /** Désigne une personne : body { "utilisateurId": ... }. */
    @PostMapping("/{id}/designes")
    public List<DocumentResponse.Ref> designer(@PathVariable UUID id, @RequestBody Map<String, UUID> body) {
        UUID utilisateurId = body.get("utilisateurId");
        if (utilisateurId == null) throw new IllegalArgumentException("La personne (utilisateurId) est obligatoire");
        return service.designer(id, utilisateurId);
    }

    @DeleteMapping("/{id}/designes/{utilisateurId}")
    public ResponseEntity<Void> retirerDesignation(@PathVariable UUID id, @PathVariable UUID utilisateurId) {
        service.retirerDesignation(id, utilisateurId);
        return ResponseEntity.noContent().build();
    }

    /** Modifie la fiche (nom, type, date, etiquettes, archivage). */
    @PutMapping("/{id}")
    public DocumentResponse update(@PathVariable UUID id, @RequestBody DocumentRequest req) {
        return service.update(id, req);
    }

    /** Verrouille ou libere le document. */
    @PatchMapping("/{id}/verrou")
    public DocumentResponse verrou(@PathVariable UUID id, @RequestParam boolean verrouille) {
        return service.setVerrou(id, verrouille);
    }

    /** Depose une nouvelle version du fichier. */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse ajouterVersion(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "observation", required = false) String observation) {
        return service.ajouterVersion(id, file, observation);
    }

    /** Rend une version anterieure courante. */
    @PatchMapping("/{id}/versions/{versionId}/default")
    public DocumentResponse restaurerVersion(@PathVariable UUID id, @PathVariable UUID versionId) {
        return service.restaurerVersion(id, versionId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        UploadDocument doc = service.loadForDownload(id);
        Resource resource = service.storage().load(doc.getFilePath());
        String downloadName = doc.getName() + (doc.getExtension() != null && !doc.getExtension().isBlank()
                ? "." + doc.getExtension() : "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(downloadName))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * En-tête {@code Content-Disposition} conforme à la RFC 6266/5987.
     *
     * <p>Le nom du document était injecté brut entre guillemets. Deux
     * conséquences observées :
     * <ul>
     *   <li>un guillemet ou une barre oblique inverse dans le nom refermait la
     *       chaîne et cassait l'en-tête ;</li>
     *   <li>un nom <b>accentué</b> le faisait disparaître complètement — la
     *       valeur d'un en-tête HTTP est de l'ISO-8859-1, un caractère hors de
     *       ce jeu rend l'en-tête invalide et le conteneur le laisse tomber
     *       silencieusement, le navigateur retombant sur l'identifiant d'URL.</li>
     * </ul>
     *
     * <p>La forme normalisée émet les deux paramètres : {@code filename} en
     * ASCII assaini pour les clients anciens, et {@code filename*} encodé en
     * UTF-8 pour tous les autres, qui le préfèrent quand les deux sont présents.
     */
    static String contentDisposition(String nom) {
        return "attachment; filename=\"" + asciiSecurise(nom) + "\"; filename*=UTF-8''" + rfc5987(nom);
    }

    /**
     * Repli ASCII : les caractères hors du jeu imprimable, ainsi que le
     * guillemet et la barre oblique inverse — qui délimitent et échappent la
     * valeur — sont remplacés par un souligné.
     */
    private static String asciiSecurise(String nom) {
        StringBuilder sb = new StringBuilder(nom.length());
        for (char c : nom.toCharArray()) {
            sb.append(c < 0x20 || c > 0x7E || c == '"' || c == '\\' ? '_' : c);
        }
        return sb.toString();
    }

    /** Encodage pourcent restreint aux {@code attr-char} de la RFC 5987. */
    private static String rfc5987(String nom) {
        StringBuilder sb = new StringBuilder();
        for (byte b : nom.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            boolean sur = (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || "!#$&+-.^_`|~".indexOf(v) >= 0;
            if (sur) {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format("%02X", v));
            }
        }
        return sb.toString();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/multiple-delete")
    public ResponseEntity<Void> multipleDelete(@RequestBody Map<String, List<UUID>> body) {
        service.multipleDelete(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/multiple-restore")
    public ResponseEntity<Void> multipleRestore(@RequestBody Map<String, List<UUID>> body) {
        service.multipleRestore(body.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }
}
