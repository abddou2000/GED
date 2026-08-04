package com.ipt.ged.document;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Logique métier du dépôt de documents (Phase 1) : upload avec validation des
 * contraintes du type (formats + taille), stockage disque, liste et corbeille.
 */
@Service
public class DocumentService {

    private final UploadDocumentRepository repo;
    private final TypeDocumentRepository typeRepo;
    private final StorageService storage;
    private final SignatureService signatureService;

    public DocumentService(UploadDocumentRepository repo, TypeDocumentRepository typeRepo,
                           StorageService storage, SignatureService signatureService) {
        this.repo = repo;
        this.typeRepo = typeRepo;
        this.storage = storage;
        this.signatureService = signatureService;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(int page, int size, String search, Long workspaceId) {
        Pageable pageable = PageRequest.of(page, size);
        Page<UploadDocument> result = (workspaceId != null)
                ? repo.findByDeletedFalseAndWorkspaceIdAndNameContainingIgnoreCaseOrderByIdDesc(workspaceId, search, pageable)
                : repo.findByDeletedFalseAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, DocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> trashed(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size);
        Page<UploadDocument> result = repo.findByDeletedTrueAndNameContainingIgnoreCaseOrderByIdDesc(search, pageable);
        return PageResponse.of(result, DocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Long id) {
        return DocumentResponse.from(load(id));
    }

    /** Dépose un document : valide le fichier contre le type, le stocke, crée la fiche. */
    @Transactional
    public DocumentResponse upload(MultipartFile file, String name, Long typeDocumentId, String expirationDate) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }
        TypeDocument type = typeRepo.findById(typeDocumentId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeDocumentId));

        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String ext = extractExtension(original);

        // Contrainte de format
        List<String> allowed = splitFormats(type.getTypeAutorise());
        if (!allowed.isEmpty() && !allowed.contains(ext.toLowerCase())) {
            throw new IllegalArgumentException("Format « ." + ext + " » non autorisé (autorisés : " + String.join(", ", allowed) + ")");
        }
        // Contrainte de taille
        long maxBytes = (long) type.getTailleMaxMo() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("Fichier trop volumineux (max " + type.getTailleMaxMo() + " Mo)");
        }

        WorkSpace ws = type.getWorkspace();
        String relativePath = storage.store(file, ws.getId(), ext);

        UploadDocument doc = new UploadDocument(name != null && !name.isBlank() ? name.trim() : stripExtension(original));
        doc.setWorkspace(ws);
        doc.setTypeDocument(type);
        doc.setFileName(original);
        doc.setFilePath(relativePath);
        doc.setExtension(ext);
        doc.setSizeKo(file.getSize() / 1024);
        if (expirationDate != null && !expirationDate.isBlank()) {
            doc.setExpirationDate(LocalDate.parse(expirationDate.trim()));
        }
        UploadDocument saved = repo.save(doc);
        // Déclenche le circuit de signature (une demande par étape du workflow du dossier).
        signatureService.createForDocument(saved);
        return DocumentResponse.from(saved);
    }

    /** Entité + ressource fichier pour le téléchargement. */
    @Transactional(readOnly = true)
    public UploadDocument loadForDownload(Long id) {
        return load(id);
    }

    public StorageService storage() {
        return storage;
    }

    @Transactional
    public void softDelete(Long id) {
        load(id).setDeleted(true);
    }

    @Transactional
    public void restore(Long id) {
        load(id).setDeleted(false);
    }

    @Transactional
    public void multipleDelete(List<Long> ids) {
        repo.findByIdInAndDeletedFalse(ids).forEach(d -> d.setDeleted(true));
    }

    @Transactional
    public void multipleRestore(List<Long> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(d -> d.setDeleted(false));
    }

    /* ---------- privé ---------- */

    private UploadDocument load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
    }

    private static List<String> splitFormats(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
    }

    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
