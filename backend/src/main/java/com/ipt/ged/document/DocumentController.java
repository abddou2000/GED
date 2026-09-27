package com.ipt.ged.document;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.document.dto.DocumentRequest;
import com.ipt.ged.document.dto.DocumentResponse;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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

    /* Le dépôt (POST /api/v1/documents) est servi par le paquet depot
       (DepotController) : dépôt en deux temps et métadonnées (§5.3, §12.11). */

    /**
     * 202 Accepted quand le contenu part à l'OCR (le document est reçu mais pas
     * encore interrogeable, état {@code EN_ATTENTE_OCR} dans la réponse),
     * 201 Created sinon (§4.3.4, §12.11).
     */
    static ResponseEntity<DocumentResponse> creation(DocumentResponse r) {
        HttpStatus statut = "EN_ATTENTE_OCR".equals(r.statutOcr()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(statut).body(r);
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
    public ResponseEntity<DocumentResponse> ajouterVersion(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "observation", required = false) String observation) {
        return creation(service.ajouterVersion(id, file, observation));
    }

    /** Rend une version anterieure courante. */
    @PatchMapping("/{id}/versions/{versionId}/default")
    public DocumentResponse restaurerVersion(@PathVariable UUID id, @PathVariable UUID versionId) {
        return service.restaurerVersion(id, versionId);
    }

    /**
     * Téléchargement de la version courante, déchiffrée à la volée en flux
     * (§6.1.2) : ni copie en clair sur disque, ni fichier entier en mémoire.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        DocumentService.FichierTelecharge f = service.telecharger(id);
        ResponseEntity.BodyBuilder r = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(f.nom()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        if (f.taille() >= 0) r.contentLength(f.taille());
        StreamingResponseBody corps = sortie -> {
            try (InputStream in = f.flux()) {
                in.transferTo(sortie);
            }
        };
        return r.body(corps);
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
