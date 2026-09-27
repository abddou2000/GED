package com.ipt.ged.document;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.Limites;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.document.dto.DocumentRequest;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.DocumentDepose;
import com.ipt.ged.document.evenement.DocumentRestaure;
import com.ipt.ged.document.evenement.DocumentSupprime;
import com.ipt.ged.document.evenement.DocumentTelecharge;
import com.ipt.ged.document.evenement.MetadonneesModifiees;
import com.ipt.ged.document.evenement.VerrouModifie;
import com.ipt.ged.document.evenement.VersionAjoutee;
import com.ipt.ged.document.evenement.VersionRestauree;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.ocr.file.EnfilageOcr;
import com.ipt.ged.ocr.file.StatutOcr;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.etiquette.Etiquette;
import com.ipt.ged.etiquette.EtiquetteRepository;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workspace.WorkSpace;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Logique métier du dépôt de documents.
 *
 * <p>Dépôt et nouvelle version (§6.1.5, §12.11 temps 1) : contrôles de la
 * fiche, puis chaîne de contrôle du fichier — taille, type réel (Tika),
 * antivirus — puis écriture chiffrée (AES-256-GCM, DEK propre, empreinte
 * SHA-256), fiche, version et job OCR <b>dans une seule transaction</b>. Tout
 * ce qui peut refuser passe avant l'écriture ; si la transaction échoue après,
 * le fichier chiffré et sa clé sont détruits.
 *
 * <p>Chaque opération publie son événement de domaine
 * ({@code com.ipt.ged.document.evenement}), source du journal d'audit.
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
    private final SignatureService signatureService;
    private final EtiquetteRepository etiquetteRepo;
    private final EmployeRepository employeRepo;
    private final DocumentVersionRepository versionRepo;
    private final ControleFichiers controle;
    private final StockageChiffre stockage;
    private final EnfilageOcr ocr;
    private final ApplicationEventPublisher evenements;

    public DocumentService(UploadDocumentRepository repo, TypeDocumentRepository typeRepo,
                           SignatureService signatureService,
                           EtiquetteRepository etiquetteRepo, EmployeRepository employeRepo,
                           DocumentVersionRepository versionRepo, ControleFichiers controle,
                           StockageChiffre stockage, EnfilageOcr ocr, ApplicationEventPublisher evenements) {
        this.repo = repo;
        this.typeRepo = typeRepo;
        this.signatureService = signatureService;
        this.etiquetteRepo = etiquetteRepo;
        this.employeRepo = employeRepo;
        this.versionRepo = versionRepo;
        this.controle = controle;
        this.stockage = stockage;
        this.ocr = ocr;
        this.evenements = evenements;
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> list(int page, int size, String search, UUID workspaceId,
                                               String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<UploadDocument> result = (workspaceId != null)
                ? repo.findByDeletedFalseAndWorkspaceIdAndNameContainingIgnoreCase(workspaceId, search, pageable)
                : repo.findByDeletedFalseAndNameContainingIgnoreCase(search, pageable);
        return avecStatutsOcr(result);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> trashed(int page, int size, String search,
                                                  String sortBy, String sortDir) {
        Pageable pageable = Tri.pageable(page, size, sortBy, sortDir, TRIS, TRIS_NUM);
        Page<UploadDocument> result = repo.findByDeletedTrueAndNameContainingIgnoreCase(search, pageable);
        return avecStatutsOcr(result);
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(UUID id) {
        return reponse(load(id));
    }

    /** État OCR des versions courantes d'une page, en une requête (pas une par ligne). */
    private PageResponse<DocumentResponse> avecStatutsOcr(Page<UploadDocument> page) {
        Map<UUID, UUID> courantes = new LinkedHashMap<>();
        page.getContent().forEach(d -> courante(d).ifPresent(v -> courantes.put(d.getId(), v.getId())));
        Map<UUID, StatutOcr> statuts = ocr.statuts(List.copyOf(courantes.values()));
        return PageResponse.of(page, d -> {
            UUID v = courantes.get(d.getId());
            StatutOcr st = v != null ? statuts.get(v) : null;
            return DocumentResponse.from(d, st != null ? st.name() : null);
        });
    }

    private DocumentResponse reponse(UploadDocument d) {
        return reponse(d, null);
    }

    /** @param statutConnu statut que l'appelant vient de fixer (évite de relire la file). */
    private DocumentResponse reponse(UploadDocument d, StatutOcr statutConnu) {
        StatutOcr st = statutConnu;
        if (st == null) {
            Optional<DocumentVersion> v = courante(d);
            if (v.isPresent()) st = ocr.statuts(List.of(v.get().getId())).get(v.get().getId());
        }
        return DocumentResponse.from(d, st != null ? st.name() : null);
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

        /* TOUT ce qui peut échouer est vérifié AVANT d'écrire le fichier : le
           stockage n'est pas transactionnel. Les contrôles de la fiche d'abord,
           puis la chaîne de contrôle du fichier (taille, type réel, antivirus),
           qui n'écrit qu'en dernier. La compensation n'est qu'un filet. */
        String nomDocument = name != null && !name.isBlank() ? name.trim() : stripExtension(original);
        Limites.controler(nomDocument, "nom du document");
        LocalDate expiration = date(expirationDate, "date d'expiration");

        ControleFichiers.Depot depot = deposerFichier(type, file);

        WorkSpace ws = type.getWorkspace();
        UploadDocument doc = new UploadDocument(nomDocument);
        doc.setWorkspace(ws);
        doc.setTypeDocument(type);
        doc.setFileName(original);
        doc.setExtension(ext);
        doc.setSizeKo(depot.stockage().tailleOctets() / 1024);
        doc.setExpirationDate(expiration);
        if (createdById != null) {
            employeRepo.findById(createdById).ifPresent(doc::setCreatedBy);
        }
        appliquerEtiquettes(doc, etiquetteIds);
        UploadDocument saved = repo.save(doc);
        // Version initiale : sans elle, l'historique commencerait au deuxieme
        // depot et le fichier d'origine n'y figurerait jamais.
        DocumentVersion initiale = versionRepo.save(version(saved, original, ext, depot, "Version initiale"));
        // Reportée dans la collection en mémoire : la réponse du dépôt est
        // construite à partir de cet objet, et sans cela l'écran verrait un
        // document sans aucune version jusqu'au prochain rechargement.
        saved.getVersions().add(initiale);
        // Déclenche le circuit de signature (une demande par étape du workflow du dossier).
        signatureService.createForDocument(saved);
        StatutOcr statut = enfilerOcr(saved, initiale).orElse(null);
        evenements.publishEvent(new DocumentDepose(saved.getId(), initiale.getId(), Acteur.courant(), Instant.now(),
                saved.getName(), type.getId(), ws.getId(), original, initiale.getCleFichierId(),
                initiale.getEmpreinte(), initiale.getTypeMime(), depot.stockage().tailleOctets(),
                statut != null ? statut.name() : null));
        return reponse(saved, statut);
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
        Map<String, Object> avant = instantane(d);
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
        UploadDocument saved = repo.save(d);
        publierModifications(saved, avant, instantane(saved));
        return reponse(saved);
    }

    /** Verrouille ou libere le document. */
    @Transactional
    public DocumentResponse setVerrou(UUID id, boolean verrouille) {
        UploadDocument d = loadPourEcriture(id);
        boolean avant = d.isVerrouille();
        d.setVerrouille(verrouille);
        UploadDocument saved = repo.save(d);
        if (avant != verrouille) {
            evenements.publishEvent(new VerrouModifie(saved.getId(), versionCouranteId(saved), Acteur.courant(),
                    Instant.now(), avant, verrouille));
        }
        return reponse(saved);
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
        UUID precedente = versionCouranteId(d);

        ControleFichiers.Depot depot = deposerFichier(type, file);

        demoterPrincipales(id);
        DocumentVersion nouvelle = versionRepo.save(version(d, original, ext, depot, observation));
        // Reportée dans la collection en mémoire : la réponse, et la version
        // courante vue par la suite de la transaction, en dépendent.
        d.getVersions().add(0, nouvelle);

        // La fiche pointe toujours vers la version courante : sans cette mise a
        // jour, le telechargement servirait encore l'ancien fichier.
        d.setFileName(original);
        d.setExtension(ext);
        d.setSizeKo(depot.stockage().tailleOctets() / 1024);
        UploadDocument saved = repo.save(d);
        StatutOcr statut = enfilerOcr(saved, nouvelle).orElse(null);
        evenements.publishEvent(new VersionAjoutee(saved.getId(), nouvelle.getId(), Acteur.courant(), Instant.now(),
                precedente, original, observation, nouvelle.getCleFichierId(), nouvelle.getEmpreinte(),
                nouvelle.getTypeMime(), depot.stockage().tailleOctets(), statut != null ? statut.name() : null));
        return reponse(saved, statut);
    }

    /**
     * Rend une version anterieure courante. Elle repart à l'OCR : l'index plein
     * texte porte sur la version courante (§4.4).
     */
    @Transactional
    public DocumentResponse restaurerVersion(UUID documentId, UUID versionId) {
        UploadDocument d = loadPourEcriture(documentId);
        refuserSiEnCorbeille(d, "restauration de version");
        DocumentVersion cible = versionRepo.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version introuvable : " + versionId));
        if (!cible.getDocument().getId().equals(documentId)) {
            throw new IllegalArgumentException("Cette version n'appartient pas au document");
        }
        UUID precedente = versionCouranteId(d);
        demoterPrincipales(documentId);
        cible.setPrincipale(true);
        d.setFileName(cible.getFileName());
        d.setExtension(cible.getExtension());
        d.setSizeKo(cible.getSizeKo());
        UploadDocument saved = repo.save(d);
        StatutOcr statut = Objects.equals(precedente, cible.getId()) ? null : enfilerOcr(saved, cible).orElse(null);
        evenements.publishEvent(new VersionRestauree(saved.getId(), cible.getId(), Acteur.courant(), Instant.now(),
                precedente, statut != null ? statut.name() : null));
        return reponse(saved, statut);
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

    /**
     * Ouvre le fichier de la version courante, déchiffré en flux, pour le
     * téléchargement. L'appelant ferme le flux.
     */
    @Transactional(readOnly = true)
    public FichierTelecharge telecharger(UUID id) {
        UploadDocument d = load(id);
        DocumentVersion v = courante(d)
                .orElseThrow(() -> Refus.introuvable("aucune version pour le document " + id));
        if (v.getCleFichierId() == null) {
            // Version de l'ancien stockage en clair, pas encore reprise.
            throw Refus.introuvable("version " + v.getId() + " non reprise dans le stockage chiffré");
        }
        InputStream flux = stockage.lire(v.getCleFichierId());
        String nom = d.getName() + (d.getExtension() != null && !d.getExtension().isBlank() ? "." + d.getExtension() : "");
        evenements.publishEvent(new DocumentTelecharge(d.getId(), v.getId(), Acteur.courant(), Instant.now(),
                v.getFileName()));
        return new FichierTelecharge(nom, v.getTailleOctets() != null ? v.getTailleOctets() : -1, flux);
    }

    /** Fichier prêt à servir ; {@code taille} en octets, -1 si inconnue. */
    public record FichierTelecharge(String nom, long taille, InputStream flux) {
    }

    @Transactional
    public void softDelete(UUID id) {
        UploadDocument d = load(id);
        if (d.isDeleted()) return;
        d.mettreEnCorbeille(ActeurCourant.employeId());
        publierSuppression(d);
    }

    @Transactional
    public void restore(UUID id) {
        UploadDocument d = load(id);
        if (!d.isDeleted()) return;
        d.restaurer();
        publierRestauration(d);
    }

    @Transactional
    public void multipleDelete(List<UUID> ids) {
        repo.findByIdInAndDeletedFalse(ids).forEach(d -> {
            d.mettreEnCorbeille(ActeurCourant.employeId());
            publierSuppression(d);
        });
    }

    @Transactional
    public void multipleRestore(List<UUID> ids) {
        repo.findByIdInAndDeletedTrue(ids).forEach(d -> {
            d.restaurer();
            publierRestauration(d);
        });
    }

    private void publierSuppression(UploadDocument d) {
        evenements.publishEvent(new DocumentSupprime(d.getId(), versionCouranteId(d), Acteur.courant(),
                Instant.now(), d.getName()));
    }

    private void publierRestauration(UploadDocument d) {
        evenements.publishEvent(new DocumentRestaure(d.getId(), versionCouranteId(d), Acteur.courant(),
                Instant.now(), d.getName()));
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
     * Chaîne de contrôle et écriture chiffrée du fichier (§6.1.5) : taille et
     * formats du type documentaire, type réel, antivirus en échec fermé, puis
     * écriture. Refus : 413, 415, 422 {@code FICHIER_INFECTE}, 503.
     */
    private ControleFichiers.Depot deposerFichier(TypeDocument type, MultipartFile file) {
        ControleFichiers.Depot depot = controle.deposer(SourceFichier.de(file),
                controle.regles(type.getTailleMaxMo(), type.formatsAutorises()));
        detruireSiTransactionAnnulee(depot.stockage().id());
        return depot;
    }

    private static DocumentVersion version(UploadDocument doc, String nomFichier, String ext,
                                           ControleFichiers.Depot depot, String observation) {
        DocumentVersion v = new DocumentVersion(doc, nomFichier, ext, depot.stockage().tailleOctets() / 1024,
                observation, true);
        v.setCleFichierId(depot.stockage().id());
        v.setEmpreinte(depot.stockage().empreinte());
        v.setTypeMime(depot.typeMime());
        v.setTailleOctets(depot.stockage().tailleOctets());
        return v;
    }

    private Optional<StatutOcr> enfilerOcr(UploadDocument d, DocumentVersion v) {
        if (!ocr.actif()) return Optional.empty();
        // Le job est écrit en JDBC, avec clés étrangères vers la fiche et la
        // version : elles doivent être en base (même transaction) avant lui.
        versionRepo.flush();
        return ocr.enfiler(d.getId(), v.getId(), v.getCleFichierId(), v.getTypeMime(),
                d.getTypeDocument() != null ? d.getTypeDocument().getCode() : null);
    }

    /** Version courante : la principale, sinon la plus récente (état hérité abîmé toléré). */
    private static Optional<DocumentVersion> courante(UploadDocument d) {
        try {
            List<DocumentVersion> versions = d.getVersions();
            return versions.stream().filter(DocumentVersion::isPrincipale).findFirst()
                    .or(() -> versions.stream().max(Comparator.comparing(DocumentVersion::getId)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static UUID versionCouranteId(UploadDocument d) {
        return courante(d).map(DocumentVersion::getId).orElse(null);
    }

    /** Champs de la fiche suivis par l'audit, en valeurs simples. */
    private static Map<String, Object> instantane(UploadDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nom", d.getName());
        m.put("typeDocumentId", d.getTypeDocument() != null ? d.getTypeDocument().getId() : null);
        m.put("workspaceId", d.getWorkspace() != null ? d.getWorkspace().getId() : null);
        m.put("dateExpiration", d.getExpirationDate() != null ? d.getExpirationDate().toString() : null);
        m.put("actif", d.isActive());
        m.put("etiquetteIds", d.getEtiquettes().stream().map(Etiquette::getId).sorted().toList());
        return m;
    }

    private void publierModifications(UploadDocument d, Map<String, Object> avant, Map<String, Object> apres) {
        Map<String, Object> av = new LinkedHashMap<>();
        Map<String, Object> ap = new LinkedHashMap<>();
        avant.forEach((cle, valeur) -> {
            if (!Objects.equals(valeur, apres.get(cle))) {
                av.put(cle, valeur);
                ap.put(cle, apres.get(cle));
            }
        });
        if (!av.isEmpty()) {
            evenements.publishEvent(new MetadonneesModifiees(d.getId(), versionCouranteId(d), Acteur.courant(),
                    Instant.now(), av, ap));
        }
    }

    /**
     * Détruit le fichier chiffré et sa clé si la transaction en cours est
     * annulée : le stockage n'est pas transactionnel, et un fichier que plus
     * rien ne référence ne doit pas survivre à la fiche qui n'existera pas.
     */
    private void detruireSiTransactionAnnulee(UUID fichierId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    stockage.detruire(fichierId);
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
