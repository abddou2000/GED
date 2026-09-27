package com.ipt.ged.document;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.autorisation.evenement.AccesDocumentModifie;
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
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Logique métier du dépôt de documents (Phase 1) : upload avec validation des
 * contraintes du type (formats + taille), stockage disque, liste et corbeille.
 *
 * <h2>Droits (lot E3)</h2>
 * <p>Chaque chemin passe par le point d'application unique : les listes et
 * leurs totaux sont filtrés À LA SOURCE ({@link AccessPredicate#documents}),
 * chaque lecture ou écriture unitaire par {@link ControleAcces} — 404 pour un
 * document hors périmètre, 403 pour une permission manquante sur un document
 * visible. Consulter pour la fiche et le téléchargement ; Déposer sur le nœud
 * du type pour un dépôt ; Modifier pour la fiche, le verrou et les versions ;
 * Déplacer (et Déposer sur la destination) pour un changement de type qui
 * change l'emplacement principal ; Supprimer pour la corbeille.
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
    private final AccessPredicate droits;
    private final ControleAcces controle;
    private final DocumentRattachementRepository rattachements;
    private final DocumentConfidentielDesigneRepository designes;
    private final UtilisateurRepository utilisateurs;
    private final WorkSpaceRepository noeuds;
    private final ApplicationEventPublisher evenements;

    public DocumentService(UploadDocumentRepository repo, TypeDocumentRepository typeRepo,
                           StorageService storage, SignatureService signatureService,
                           EtiquetteRepository etiquetteRepo, EmployeRepository employeRepo,
                           DocumentVersionRepository versionRepo, AccessPredicate droits, ControleAcces controle,
                           DocumentRattachementRepository rattachements,
                           DocumentConfidentielDesigneRepository designes, UtilisateurRepository utilisateurs,
                           WorkSpaceRepository noeuds, ApplicationEventPublisher evenements) {
        this.repo = repo;
        this.typeRepo = typeRepo;
        this.storage = storage;
        this.signatureService = signatureService;
        this.etiquetteRepo = etiquetteRepo;
        this.employeRepo = employeRepo;
        this.versionRepo = versionRepo;
        this.droits = droits;
        this.controle = controle;
        this.rattachements = rattachements;
        this.designes = designes;
        this.utilisateurs = utilisateurs;
        this.noeuds = noeuds;
        this.evenements = evenements;
    }

    private static Authentication appelant() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * Documents vivants visibles de l'appelant. Filtre par dossier : le document
     * figure dans chacun de ses emplacements (principal ou rattachement, §12.4).
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(int page, int size, String search, UUID workspaceId,
                                               String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Specification<UploadDocument> spec = criteres(false, search)
                .and(droits.documents(appelant(), CodePermission.CONSULTER));
        if (workspaceId != null) spec = spec.and(dansLeNoeud(workspaceId));
        return pageDe(repo.findAll(spec, pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> trashed(int page, int size, String search,
                                                  String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Specification<UploadDocument> spec = criteres(true, search)
                .and(droits.documents(appelant(), CodePermission.CONSULTER));
        return pageDe(repo.findAll(spec, pageable));
    }

    private static Specification<UploadDocument> criteres(boolean supprimes, String search) {
        String motif = "%" + (search == null ? "" : search).toLowerCase() + "%";
        return (r, q, cb) -> cb.and(cb.equal(r.get("supprime"), supprimes), cb.like(cb.lower(r.get("name")), motif));
    }

    private static Specification<UploadDocument> dansLeNoeud(UUID noeudId) {
        return (r, q, cb) -> {
            Subquery<Integer> sq = q.subquery(Integer.class);
            Root<DocumentRattachement> rr = sq.from(DocumentRattachement.class);
            sq.select(cb.literal(1)).where(cb.equal(rr.get("document"), r), cb.equal(rr.get("noeud").get("id"), noeudId));
            return cb.or(cb.equal(r.get("workspace").get("id"), noeudId), cb.exists(sq));
        };
    }

    /**
     * Emplacement affiché dans une liste (§12.4) : le principal s'il est
     * accessible à l'appelant, sinon son premier rattachement accessible.
     */
    private PageResponse<DocumentResponse> pageDe(Page<UploadDocument> page) {
        Set<UUID> accessibles = droits.noeudsAccessibles(appelant(), CodePermission.CONSULTER);
        Map<UUID, WorkSpace> affiche = new HashMap<>();
        for (UploadDocument d : page.getContent()) {
            if (d.getWorkspace() == null || accessibles.contains(d.getWorkspace().getId())) continue;
            rattachements.findByDocumentIdOrderByCreeLeAsc(d.getId()).stream()
                    .map(DocumentRattachement::getNoeud)
                    .filter(n -> accessibles.contains(n.getId()))
                    .findFirst().ifPresent(n -> affiche.put(d.getId(), n));
        }
        return PageResponse.of(page, d -> DocumentResponse.from(d, affiche.getOrDefault(d.getId(), d.getWorkspace()),
                null, null));
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        controle.exigerLectureDocument(id);
        return fiche(load(id));
    }

    /** Fiche complète : permissions de l'appelant et emplacements complémentaires visibles. */
    private DocumentResponse fiche(UploadDocument d) {
        Set<CodePermission> perms = droits.permissionsSurDocument(appelant(), d.getId());
        Set<UUID> visibles = droits.noeudsVisibles(appelant()).keySet();
        List<DocumentResponse.Ref> autres = rattachements.findByDocumentIdOrderByCreeLeAsc(d.getId()).stream()
                .map(DocumentRattachement::getNoeud)
                .filter(n -> visibles.contains(n.getId()))
                .map(n -> new DocumentResponse.Ref(n.getId(), n.getName()))
                .toList();
        return DocumentResponse.from(d, d.getWorkspace(), perms.stream().map(Enum::name).sorted().toList(), autres);
    }

    /** Dépose un document : valide le fichier contre le type, le stocke, crée la fiche. */
    @Transactional
    public DocumentResponse upload(MultipartFile file, String name, UUID typeDocumentId,
                                   String expirationDate, UUID createdById, List<UUID> etiquetteIds) {
        return upload(file, name, typeDocumentId, expirationDate, createdById, etiquetteIds, null);
    }

    /**
     * @param confidentialite niveau choisi au dépôt ; absent = défaut du type (§12.3)
     */
    @Transactional
    public DocumentResponse upload(MultipartFile file, String name, UUID typeDocumentId,
                                   String expirationDate, UUID createdById, List<UUID> etiquetteIds,
                                   Confidentialite confidentialite) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est obligatoire");
        }
        TypeDocument type = typeRepo.findById(typeDocumentId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeDocumentId));
        // Déposer s'exerce sur l'emplacement principal, fixé par le type.
        controle.exigerSurNoeud(CodePermission.DEPOSER, type.getWorkspace().getId());
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
        doc.setConfidentialite(confidentialite != null ? confidentialite
                : type.getConfidentialiteDefaut() != null ? type.getConfidentialiteDefaut() : Confidentialite.PUBLIC);
        if (createdById != null) {
            employeRepo.findById(createdById).ifPresent(doc::setCreatedBy);
        }
        appliquerEtiquettes(doc, etiquetteIds);
        UploadDocument saved = repo.save(doc);
        if (saved.getConfidentialite() == Confidentialite.CONFIDENTIEL) {
            designerDeposant(saved);
        }
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
        return fiche(saved);
    }

    /**
     * Modifie la fiche : nom, type, date d'expiration, etiquettes, archivage.
     * Le fichier n'est pas touche ici — le remplacer passe par une version.
     */
    @Transactional
    public DocumentResponse update(UUID id, DocumentRequest req) {
        controle.exigerSurDocument(CodePermission.MODIFIER, id);
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
            if (!type.getWorkspace().getId().equals(d.getWorkspace().getId())) {
                // Changer de type change l'emplacement principal : c'est un
                // déplacement (§12.5), Déplacer sur le document et Déposer sur
                // la destination. Les rattachements complémentaires restent.
                controle.exigerSurDocument(CodePermission.DEPLACER, id);
                controle.exigerSurNoeud(CodePermission.DEPOSER, type.getWorkspace().getId());
                rattachements.findByDocumentIdAndNoeudId(id, type.getWorkspace().getId())
                        .ifPresent(rattachements::delete);
            }
            d.setTypeDocument(type);
            // Le dossier suit le type : les laisser diverger rangerait le document
            // dans un espace qui n'accepte pas ce type.
            d.setWorkspace(type.getWorkspace());
        }
        if (req.confidentialite() != null && req.confidentialite() != d.getConfidentialite()) {
            changerConfidentialite(d, req.confidentialite());
        }
        d.setExpirationDate(date(req.expirationDate(), "date d'expiration"));
        if (req.active() != null) {
            d.setActive(req.active());
        }
        appliquerEtiquettes(d, req.etiquetteIds());
        UploadDocument maj = repo.save(d);
        return controle.documentLisible(id) ? fiche(maj) : DocumentResponse.from(maj);
    }

    /** Verrouille ou libere le document. */
    @Transactional
    public DocumentResponse setVerrou(UUID id, boolean verrouille) {
        controle.exigerSurDocument(CodePermission.MODIFIER, id);
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
        controle.exigerSurDocument(CodePermission.MODIFIER, id);
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
        controle.exigerSurDocument(CodePermission.MODIFIER, documentId);
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
        controle.exigerLectureDocument(id);
        return load(id);
    }

    public StorageService storage() {
        return storage;
    }

    /**
     * Suppression depuis l'emplacement principal (§12.4) : le document entier
     * passe en corbeille et disparaît de TOUS ses emplacements.
     */
    @Transactional
    public void softDelete(UUID id) {
        controle.exigerSurDocument(CodePermission.SUPPRIMER, id);
        load(id).mettreEnCorbeille(ActeurCourant.employeId());
    }

    @Transactional
    public void restore(UUID id) {
        controle.exigerSurDocument(CodePermission.SUPPRIMER, id);
        load(id).restaurer();
    }

    /** Tout ou rien : un seul document refusé (404 / 403) et rien n'est supprimé. */
    @Transactional
    public void multipleDelete(List<UUID> ids) {
        List<UploadDocument> l = repo.findByIdInAndSupprimeFalse(ids);
        l.forEach(d -> controle.exigerSurDocument(CodePermission.SUPPRIMER, d.getId()));
        l.forEach(d -> d.mettreEnCorbeille(ActeurCourant.employeId()));
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        List<UploadDocument> l = repo.findByIdInAndSupprimeTrue(ids);
        l.forEach(d -> controle.exigerSurDocument(CodePermission.SUPPRIMER, d.getId()));
        l.forEach(UploadDocument::restaurer);
    }

    /* ---------- rattachements (§12.4) ---------- */

    /** Emplacements complémentaires visibles de l'appelant. */
    @Transactional(readOnly = true)
    public List<DocumentResponse.Ref> rattachements(UUID documentId) {
        controle.exigerLectureDocument(documentId);
        return fiche(load(documentId)).rattachements();
    }

    /**
     * Rattache le document à un nœud supplémentaire : écriture sur l'origine
     * (Modifier sur le document) et sur la destination (Déposer sur le nœud).
     * Aucun fichier n'est copié ; le rattachement au nœud principal est refusé.
     */
    @Transactional
    public DocumentResponse rattacher(UUID documentId, UUID noeudId) {
        controle.exigerSurDocument(CodePermission.MODIFIER, documentId);
        UploadDocument d = loadPourEcriture(documentId);
        controle.exigerSurNoeud(CodePermission.DEPOSER, noeudId);
        WorkSpace noeud = noeuds.findById(noeudId)
                .orElseThrow(() -> new EntityNotFoundException("Espace de travail introuvable : " + noeudId));
        if (noeud.isSupprime()) {
            throw new IllegalArgumentException("Espace de travail en corbeille : rattachement impossible.");
        }
        if (d.getWorkspace().getId().equals(noeudId)) {
            throw new IllegalArgumentException("Le document est déjà rangé dans cet espace (emplacement principal).");
        }
        if (rattachements.existsByDocumentIdAndNoeudId(documentId, noeudId)) {
            throw new DuplicateKeyException("Le document est déjà rattaché à cet espace.");
        }
        rattachements.save(new DocumentRattachement(d, noeud, ActeurCourant.utilisateurId()));
        publier(AccesDocumentModifie.RATTACHEMENT_AJOUTE, documentId, noeudId, null, null);
        return fiche(d);
    }

    /**
     * Suppression depuis un rattachement (§12.4) : seule la ligne de
     * rattachement disparaît, sans effet sur le document ni sur ses autres
     * emplacements.
     */
    @Transactional
    public void detacher(UUID documentId, UUID noeudId) {
        controle.exigerSurDocument(CodePermission.MODIFIER, documentId);
        loadPourEcriture(documentId);
        DocumentRattachement r = rattachements.findByDocumentIdAndNoeudId(documentId, noeudId)
                .orElseThrow(() -> new EntityNotFoundException("Rattachement introuvable"));
        controle.exigerSurNoeud(CodePermission.DEPOSER, noeudId);
        rattachements.delete(r);
        publier(AccesDocumentModifie.RATTACHEMENT_RETIRE, documentId, noeudId, null, null);
    }

    /* ---------- confidentialité (§12.3) ---------- */

    /** Personnes désignées d'un document (visible des personnes qui voient le document). */
    @Transactional(readOnly = true)
    public List<DocumentResponse.Ref> designes(UUID documentId) {
        controle.exigerLectureDocument(documentId);
        List<UUID> ids = designes.findByDocumentIdOrderByCreeLeAsc(documentId).stream()
                .map(DocumentConfidentielDesigne::getUtilisateurId).toList();
        Map<UUID, Utilisateur> parId = new HashMap<>();
        utilisateurs.findAllById(ids).forEach(u -> parId.put(u.getId(), u));
        List<DocumentResponse.Ref> l = new ArrayList<>();
        for (UUID id : ids) {
            Utilisateur u = parId.get(id);
            if (u != null) l.add(new DocumentResponse.Ref(id, u.getEmploye().getFullName()));
        }
        return l;
    }

    /**
     * Désigne une personne : l'Administrateur ou toute personne qui voit le
     * document. Effet immédiat, audité.
     */
    @Transactional
    public List<DocumentResponse.Ref> designer(UUID documentId, UUID utilisateurId) {
        controle.exigerLectureDocument(documentId);
        loadPourEcriture(documentId);
        if (!utilisateurs.existsById(utilisateurId)) {
            throw new EntityNotFoundException("Identité introuvable : " + utilisateurId);
        }
        if (!designes.existsByDocumentIdAndUtilisateurId(documentId, utilisateurId)) {
            designes.save(new DocumentConfidentielDesigne(documentId, utilisateurId, ActeurCourant.utilisateurId()));
            publier(AccesDocumentModifie.DESIGNATION_AJOUTEE, documentId, utilisateurId, null, null);
        }
        return designes(documentId);
    }

    /** Retire une désignation : effet immédiat (la décision relit la table à chaque accès). */
    @Transactional
    public void retirerDesignation(UUID documentId, UUID utilisateurId) {
        controle.exigerLectureDocument(documentId);
        loadPourEcriture(documentId);
        DocumentConfidentielDesigne x = designes.findByDocumentIdAndUtilisateurId(documentId, utilisateurId)
                .orElseThrow(() -> new EntityNotFoundException("Désignation introuvable"));
        designes.delete(x);
        publier(AccesDocumentModifie.DESIGNATION_RETIREE, documentId, utilisateurId, null, null);
    }

    /**
     * Change le niveau (Modifier, déjà vérifié). Passer en CONFIDENTIEL désigne
     * le déposant s'il ne l'est pas : faute de quoi il perdrait l'accès à son
     * propre dépôt (§12.3).
     */
    private void changerConfidentialite(UploadDocument d, Confidentialite niveau) {
        Confidentialite avant = d.getConfidentialite();
        d.setConfidentialite(niveau);
        if (niveau == Confidentialite.CONFIDENTIEL) designerDeposant(d);
        publier(AccesDocumentModifie.CONFIDENTIALITE_MODIFIEE, d.getId(), null, avant.name(), niveau.name());
    }

    /** Le déposant d'un document confidentiel est désigné par défaut (§12.3). */
    private void designerDeposant(UploadDocument d) {
        if (d.getCreatedBy() == null) return;
        utilisateurs.findByEmployeId(d.getCreatedBy().getId()).ifPresent(u -> {
            if (!designes.existsByDocumentIdAndUtilisateurId(d.getId(), u.getId())) {
                designes.save(new DocumentConfidentielDesigne(d.getId(), u.getId(), ActeurCourant.utilisateurId()));
                publier(AccesDocumentModifie.DESIGNATION_AJOUTEE, d.getId(), u.getId(), null, null);
            }
        });
    }

    private void publier(String type, UUID documentId, UUID cible, String avant, String apres) {
        evenements.publishEvent(new AccesDocumentModifie(type, documentId, cible, avant, apres,
                ActeurCourant.utilisateurId(), Instant.now()));
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
        if (d.isSupprime()) {
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
