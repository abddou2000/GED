package com.ipt.ged.document;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.Limites;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.document.dto.DocumentRequest;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.etiquette.Etiquette;
import com.ipt.ged.etiquette.EtiquetteRepository;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Logique métier du dépôt de documents (Phase 1) : upload avec validation des
 * contraintes du type (formats + taille), stockage disque, liste et corbeille.
 */
@Service
public class DocumentService {

    /** Colonnes sur lesquelles le tri est accepte ; toute autre valeur est ignoree. */
    private static final Set<String> TRIS = Set.of(
            "id", "name", "extension", "sizeKo", "createdAt", "expirationDate",
            "workspace.name", "typeDocument.typeDeDocument");

    /** Colonnes numeriques ou temporelles : triees telles quelles. */
    private static final Set<String> TRIS_NUM = Set.of("id", "sizeKo", "createdAt", "expirationDate");

    private final UploadDocumentRepository repo;
    private final TypeDocumentRepository typeRepo;
    private final StorageService storage;
    private final SignatureService signatureService;
    private final EtiquetteRepository etiquetteRepo;
    private final EmployeRepository employeRepo;
    private final DocumentVersionRepository versionRepo;

    public DocumentService(UploadDocumentRepository repo, TypeDocumentRepository typeRepo,
                           StorageService storage, SignatureService signatureService,
                           EtiquetteRepository etiquetteRepo, EmployeRepository employeRepo,
                           DocumentVersionRepository versionRepo) {
        this.repo = repo;
        this.typeRepo = typeRepo;
        this.storage = storage;
        this.signatureService = signatureService;
        this.etiquetteRepo = etiquetteRepo;
        this.employeRepo = employeRepo;
        this.versionRepo = versionRepo;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(int page, int size, String search, UUID workspaceId,
                                               String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<UploadDocument> result = (workspaceId != null)
                ? repo.findByDeletedFalseAndWorkspaceIdAndNameContainingIgnoreCase(workspaceId, search, pageable)
                : repo.findByDeletedFalseAndNameContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, DocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> trashed(int page, int size, String search,
                                                  String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<UploadDocument> result = repo.findByDeletedTrueAndNameContainingIgnoreCase(search, pageable);
        return PageResponse.of(result, DocumentResponse::from);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        return DocumentResponse.from(load(id));
    }

    /** Dépose un document : valide le fichier contre le type, le stocke, crée la fiche. */
    @Transactional
    public DocumentResponse upload(MultipartFile file, String name, UUID typeDocumentId,
                                   String expirationDate, UUID createdById, List<UUID> etiquetteIds) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }
        TypeDocument type = typeRepo.findById(typeDocumentId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeDocumentId));
        ContraintesDepot.validerTypeVivant(type);

        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String ext = extractExtension(original);

        ContraintesDepot.valider(type, file);

        /* TOUT ce qui peut échouer est vérifié AVANT d'écrire sur le disque.
           Le disque n'est pas transactionnel : `storage.store()` précédait
           l'analyse de la date d'expiration, si bien qu'une date invalide
           annulait la base — et laissait le fichier. Zéro document, un fichier
           orphelin, aucun moyen de le retrouver. L'ordre des opérations est ici
           la vraie correction ; la compensation ci-dessous n'est qu'un filet. */
        String nomDocument = name != null && !name.isBlank() ? name.trim() : stripExtension(original);
        Limites.controler(nomDocument, "nom du document");
        LocalDate expiration = date(expirationDate, "date d'expiration");

        WorkSpace ws = type.getWorkspace();
        String relativePath = storage.store(file, ws.getId(), ext);
        // Filet : si la transaction est annulée après ce point (contrainte de
        // base, panne), le fichier écrit ne doit pas survivre à la fiche qui
        // n'existera pas.
        supprimerSiTransactionAnnulee(relativePath);

        UploadDocument doc = new UploadDocument(nomDocument);
        doc.setWorkspace(ws);
        doc.setTypeDocument(type);
        doc.setFileName(original);
        doc.setFilePath(relativePath);
        doc.setExtension(ext);
        doc.setSizeKo(file.getSize() / 1024);
        doc.setExpirationDate(expiration);
        if (createdById != null) {
            employeRepo.findById(createdById).ifPresent(doc::setCreatedBy);
        }
        appliquerEtiquettes(doc, etiquetteIds);
        UploadDocument saved = repo.save(doc);
        // Version initiale : sans elle, l'historique commencerait au deuxieme
        // depot et le fichier d'origine n'y figurerait jamais.
        DocumentVersion initiale = versionRepo.save(new DocumentVersion(saved, original, relativePath, ext,
                file.getSize() / 1024, "Version initiale", true));
        // Reportée dans la collection en mémoire : la réponse du dépôt est
        // construite à partir de cet objet, et sans cela l'écran verrait un
        // document sans aucune version jusqu'au prochain rechargement.
        saved.getVersions().add(initiale);
        // Déclenche le circuit de signature (une demande par étape du workflow du dossier).
        signatureService.createForDocument(saved);
        return DocumentResponse.from(saved);
    }

    /**
     * Modifie la fiche : nom, type, date d'expiration, etiquettes, archivage.
     * Le fichier n'est pas touche ici — le remplacer passe par une version.
     */
    @Transactional
    public DocumentResponse update(UUID id, DocumentRequest req) {
        UploadDocument d = loadPourEcriture(id);
        if (d.isVerrouille()) {
            throw new IllegalArgumentException("Document verrouille : modification impossible");
        }
        if (req.name() != null && !req.name().isBlank()) {
            Limites.controler(req.name().trim(), "nom du document");
            d.setName(req.name().trim());
        }
        if (req.typeDocumentId() != null) {
            TypeDocument type = typeRepo.findById(req.typeDocumentId())
                    .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + req.typeDocumentId()));
            ContraintesDepot.validerTypeVivant(type);
            d.setTypeDocument(type);
            // Le dossier suit le type : les laisser diverger rangerait le document
            // dans un espace qui n'accepte pas ce type.
            d.setWorkspace(type.getWorkspace());
        }
        d.setExpirationDate(date(req.expirationDate(), "date d'expiration"));
        if (req.active() != null) {
            d.setActive(req.active());
        }
        appliquerEtiquettes(d, req.etiquetteIds());
        return DocumentResponse.from(repo.save(d));
    }

    /** Verrouille ou libere le document. */
    @Transactional
    public DocumentResponse setVerrou(UUID id, boolean verrouille) {
        UploadDocument d = loadPourEcriture(id);
        d.setVerrouille(verrouille);
        return DocumentResponse.from(repo.save(d));
    }

    /**
     * Depose une nouvelle version : le fichier precedent reste consultable.
     *
     * <p>Le document est verrouillé en base <b>avant</b> toute lecture des
     * versions. Deux dépôts simultanés lisaient auparavant la même « version
     * principale », la démotaient chacun puis promouvaient la leur : le document
     * finissait avec deux drapeaux à {@code true}, et toute lecture ultérieure
     * — y compris la simple consultation de la fiche — tombait en 500 sans
     * aucun moyen de réparation par l'API. Le verrou sérialise les deux
     * requêtes : la seconde reprend l'état laissé par la première.
     */
    @Transactional
    public DocumentResponse ajouterVersion(UUID id, MultipartFile file, String observation) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }
        UploadDocument d = loadPourEcriture(id);
        refuserSiEnCorbeille(d, "nouvelle version");
        if (d.isVerrouille()) {
            throw new IllegalArgumentException("Document verrouille : nouvelle version impossible");
        }
        TypeDocument type = d.getTypeDocument();
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String ext = extractExtension(original);

        ContraintesDepot.valider(type, file);

        String relativePath = storage.store(file, d.getWorkspace().getId(), ext);
        supprimerSiTransactionAnnulee(relativePath);
        demoterPrincipales(id);
        versionRepo.save(new DocumentVersion(d, original, relativePath, ext,
                file.getSize() / 1024, observation, true));

        // La fiche pointe toujours vers la version courante : sans cette mise a
        // jour, le telechargement servirait encore l'ancien fichier.
        d.setFileName(original);
        d.setFilePath(relativePath);
        d.setExtension(ext);
        d.setSizeKo(file.getSize() / 1024);
        return DocumentResponse.from(repo.save(d));
    }

    /** Rend une version anterieure courante. */
    @Transactional
    public DocumentResponse restaurerVersion(UUID documentId, UUID versionId) {
        UploadDocument d = loadPourEcriture(documentId);
        refuserSiEnCorbeille(d, "restauration de version");
        DocumentVersion cible = versionRepo.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version introuvable : " + versionId));
        if (!cible.getDocument().getId().equals(documentId)) {
            throw new IllegalArgumentException("Cette version n'appartient pas au document");
        }
        demoterPrincipales(documentId);
        cible.setPrincipale(true);
        d.setFileName(cible.getFileName());
        d.setFilePath(cible.getFilePath());
        d.setExtension(cible.getExtension());
        d.setSizeKo(cible.getSizeKo());
        return DocumentResponse.from(repo.save(d));
    }

    /**
     * Retire le drapeau « principale » de TOUTES les versions qui le portent.
     *
     * <p>Le code d'origine n'en démotait qu'une, en supposant l'invariant tenu.
     * Un document déjà abîmé par la course restait donc abîmé quoi qu'on fasse.
     * Traiter la liste entière rend l'état réparable : une nouvelle version, ou
     * la restauration d'une ancienne, suffit à revenir à une seule principale.
     */
    private void demoterPrincipales(UUID documentId) {
        versionRepo.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId)
                .forEach(v -> v.setPrincipale(false));
    }

    private void appliquerEtiquettes(UploadDocument d, List<UUID> ids) {
        if (ids == null) return;
        List<Etiquette> tags = ids.isEmpty() ? List.of() : etiquetteRepo.findAllById(ids);
        d.getEtiquettes().clear();
        d.getEtiquettes().addAll(tags);
    }

    /** Entité + ressource fichier pour le téléchargement. */
    @Transactional(readOnly = true)
    public UploadDocument loadForDownload(UUID id) {
        return load(id);
    }

    public StorageService storage() {
        return storage;
    }

    @Transactional
    public void softDelete(UUID id) {
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
    }

    @Transactional
    public void restore(UUID id) {
        load(id).restaurer();
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndDeletedFalse(ids).forEach(d -> d.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(d -> d.restaurer());
    }

    /* ---------- privé ---------- */

    private UploadDocument load(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
    }

    /** Chargement verrouillé, pour toute opération qui écrit sur le document. */
    private UploadDocument loadPourEcriture(UUID id) {
        UploadDocument d = repo.findByIdPourEcriture(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
        refuserSiEnCorbeille(d, "modification");
        return d;
    }

    /**
     * Règle de la corbeille : <b>la fiche reste lisible, toute écriture est
     * refusée</b>.
     *
     * <p>Fermer aussi la lecture rendrait la corbeille inutilisable : l'écran
     * « éléments supprimés » liste ces documents et doit pouvoir les décrire
     * avant qu'on décide de les restaurer ou de les purger. En revanche rien
     * n'y est modifiable — renommer, verrouiller, verser une version ou signer
     * un document que l'utilisateur croit supprimé produit une trace que
     * personne ne relira.
     */
    private static void refuserSiEnCorbeille(UploadDocument d, String operation) {
        if (d.isDeleted()) {
            throw new IllegalArgumentException("Document en corbeille : " + operation
                    + " impossible. Restaurez-le d'abord.");
        }
    }

    /**
     * Analyse une date ISO en refusant proprement ce qui n'en est pas une.
     *
     * <p>{@code LocalDate.parse} lève une {@code DateTimeParseException}, qui
     * n'est pas une {@code IllegalArgumentException} : elle traversait le
     * gestionnaire d'erreurs et sortait en 500 pour une simple faute de saisie.
     */
    private static LocalDate date(String valeur, String champ) {
        if (valeur == null || valeur.isBlank()) return null;
        try {
            return LocalDate.parse(valeur.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Le champ « " + champ
                    + " » attend une date au format AAAA-MM-JJ (reçu : « " + valeur.trim() + " »).");
        }
    }

    /**
     * Programme la suppression du fichier si la transaction en cours est
     * annulée.
     *
     * <p>C'est la compensation du seul point où l'application écrit sur un
     * support non transactionnel. Elle s'exécute <b>après</b> la fin de la
     * transaction : à ce moment la base a déjà tout rejeté, le fichier n'est
     * référencé par rien, et le supprimer est le seul moyen de ne pas accumuler
     * des orphelins invisibles.
     */
    private void supprimerSiTransactionAnnulee(String cheminRelatif) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.supprimer(cheminRelatif);
                }
            }
        });
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
