package com.ipt.ged.cycledevie;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.cycledevie.conservation.CopiesConservation;
import com.ipt.ged.document.DocumentVersion;
import com.ipt.ged.document.archivage.ArchivageDocuments;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.DocumentArchive;
import com.ipt.ged.document.evenement.DocumentDesarchive;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.integrite.VerificationIntegrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Archivage d'un document (§12.6, revue client D10) : action <b>manuelle</b>
 * uniquement, jamais automatique.
 *
 * <p>Étapes par document : vérification de l'empreinte SHA-256 de la version
 * courante, copie de conservation PDF/A-2 (Word compris), inscription du
 * statut, de la date et de l'archiviste, événement d'audit. Si la conversion
 * échoue, l'archivage n'est pas bloqué : le document est archivé avec son
 * original, l'anomalie est journalisée et la copie est signalée pour reprise
 * ({@code copie_conservation.statut = ECHEC}).
 *
 * <p>Deux phases, pour ne pas tenir de verrou pendant une conversion :
 * {@link #preparer} (hors transaction : contrôles, empreinte, conversion,
 * chiffrement de la copie) puis {@link #appliquer} (dans la transaction de
 * l'appelant : document reverrouillé et recontrôlé, statut, copie, audit). Une
 * copie préparée dont l'application n'aboutit pas est détruite.
 */
@Service
public class ArchivageService {

    private static final Logger log = LoggerFactory.getLogger(ArchivageService.class);

    private final UploadDocumentRepository documents;
    private final ControleAcces controle;
    private final CopiesConservation copies;
    private final VerificationIntegrite integrite;
    private final StockageChiffre stockage;
    private final ApplicationEventPublisher evenements;
    private final ArchivageDocuments statuts;
    private final TransactionTemplate transaction;
    private final TransactionTemplate lecture;

    public ArchivageService(UploadDocumentRepository documents, ControleAcces controle,
                            CopiesConservation copies, VerificationIntegrite integrite, StockageChiffre stockage,
                            ApplicationEventPublisher evenements, ArchivageDocuments statuts,
                            PlatformTransactionManager transactions) {
        this.documents = documents;
        this.controle = controle;
        this.copies = copies;
        this.integrite = integrite;
        this.stockage = stockage;
        this.evenements = evenements;
        this.statuts = statuts;
        this.transaction = new TransactionTemplate(transactions);
        this.lecture = new TransactionTemplate(transactions);
        this.lecture.setReadOnly(true);
    }

    /** Issue de l'archivage d'un document (élément de job ou archivage unitaire). */
    public enum Issue {
        /** Archivé avec sa copie PDF/A-2 validée. */
        ARCHIVE,
        /** Archivé avec son seul original : copie PDF/A en échec, signalée pour reprise. */
        ANOMALIE,
        DEJA_ARCHIVE,
        /** Sorti du périmètre entre la sélection et le traitement (corbeille, verrou). */
        IGNORE,
        /** Non archivé : empreinte divergente, fichier non repris, document modifié… */
        ECHEC
    }

    /** Refus de préparation : issue, code d'erreur et motif. */
    public record Rejet(Issue issue, String code, String motif) {
    }

    /**
     * Document prêt à être archivé, ou refusé.
     *
     * @param copie copie produite ({@code null} si refusé).
     */
    public record Preparation(UUID documentId, UUID versionId, String empreinte, String nom,
                              CopiesConservation.Production copie, Rejet refus) {
        boolean refusee() {
            return refus != null;
        }
    }

    public record Resultat(UUID documentId, Issue issue, String copieConservation, String motif) {
    }

    /* ======================= Archivage unitaire ======================= */

    /** Archive un document (action manuelle) ; 409 si son état l'interdit, 403 sans la permission. */
    public Resultat archiver(UUID documentId) {
        Acteur acteur = Acteur.courant();
        // Permission Archiver (Agent d'archive, Administrateur) : 404 hors périmètre, 403 sinon.
        controle.exigerSurDocument(CodePermission.ARCHIVER, documentId);
        Preparation p = preparer(documentId);
        if (p.refusee()) {
            throw p.refus().code().equals(com.ipt.ged.fichier.CodesErreurFichier.INTEGRITE_COMPROMISE)
                    ? Refus.integriteCompromise(p.refus().motif(), null)
                    : ErreurCycleDeVie.conflit(p.refus().code(), p.refus().motif());
        }
        UUID archiviste = ActeurCourant.utilisateurId();
        Resultat r;
        try {
            r = transaction.execute(s -> appliquer(p, acteur, archiviste, null));
        } catch (RuntimeException e) {
            abandonner(p);
            throw e;
        }
        if (r.issue() != Issue.ARCHIVE && r.issue() != Issue.ANOMALIE) {
            abandonner(p);
            throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.DOCUMENT_MODIFIE, r.motif());
        }
        return r;
    }

    /** Désarchive un document (réservé), empreinte revérifiée. */
    @Transactional
    public void desarchiver(UUID documentId) {
        UploadDocument d = documents.findByIdPourEcriture(documentId)
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Document " + documentId));
        // Désarchivage réservé : même permission Archiver (§12.6).
        controle.exigerSurDocument(CodePermission.ARCHIVER, documentId);
        if (!d.estArchive()) {
            throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.DOCUMENT_NON_ARCHIVE, "Le document n'est pas archivé.");
        }
        DocumentVersion v = courante(d).orElse(null);
        if (v != null && v.getCleFichierId() != null) {
            VerificationIntegrite.Resultat r = integrite.verifier(v.getCleFichierId(), v.getEmpreinte(),
                    "désarchivage du document " + documentId);
            if (!r.conforme()) {
                throw Refus.integriteCompromise("empreinte divergente au désarchivage (" + r.statut() + ")", null);
            }
        }
        // Statut porté par le contrat du lot modèle (dev1), dans cette transaction.
        statuts.desarchiver(documentId);
        evenements.publishEvent(new DocumentDesarchive(d.getId(), v != null ? v.getId() : null, Acteur.courant(),
                Instant.now(), v != null ? v.getEmpreinte() : null));
    }

    /** État de conservation d'un document et de la copie PDF/A de sa version courante. */
    public record Conservation(UUID documentId, String statutConservation, Instant archiveLe, UUID archivePar,
                               UUID versionId, CopiesConservation.Info copie) {
    }

    public Conservation conservation(UUID documentId) {
        return lecture.execute(s -> {
            UploadDocument d = documents.findById(documentId)
                    .orElseThrow(() -> ErreurCycleDeVie.introuvable("Document " + documentId));
            UUID v = courante(d).map(DocumentVersion::getId).orElse(null);
            return new Conservation(documentId, d.getStatutConservation().name(), d.getArchiveLe(), d.getArchivePar(),
                    v, v != null ? copies.info(v).orElse(null) : null);
        });
    }

    /* ======================= Phases ======================= */

    /**
     * Phase 1, hors transaction : état du document, empreinte de la version
     * courante recalculée et comparée (§6.1.4), copie de conservation produite
     * et chiffrée.
     */
    public Preparation preparer(UUID documentId) {
        Object etat = lecture.execute(s -> {
            UploadDocument d = documents.findById(documentId).orElse(null);
            if (d == null) return refus(documentId, Issue.IGNORE, ErreurCycleDeVie.INTROUVABLE, "document introuvable");
            if (d.isSupprime()) {
                return refus(documentId, Issue.IGNORE, ErreurCycleDeVie.DOCUMENT_EN_CORBEILLE, "document en corbeille");
            }
            if (d.estArchive()) {
                return refus(documentId, Issue.DEJA_ARCHIVE, ErreurCycleDeVie.DOCUMENT_ARCHIVE, "document déjà archivé");
            }
            if (d.isVerrouille()) {
                return refus(documentId, Issue.IGNORE, ErreurCycleDeVie.DOCUMENT_VERROUILLE,
                        "document verrouillé : le verrou gèle aussi l'archivage");
            }
            DocumentVersion v = courante(d).orElse(null);
            if (v == null || v.getCleFichierId() == null || v.getEmpreinte() == null) {
                return refus(documentId, Issue.ECHEC, ErreurCycleDeVie.FICHIER_NON_REPRIS,
                        "version courante absente ou non reprise dans le stockage chiffré");
            }
            return new Instantane(v.getId(), v.getCleFichierId(), v.getEmpreinte(), v.getTypeMime(), d.getName());
        });
        if (etat instanceof Preparation refusee) return refusee;
        Instantane i = (Instantane) etat;

        VerificationIntegrite.Resultat verif = integrite.verifier(i.cleFichierId(), i.empreinte(),
                "archivage du document " + documentId + ", version " + i.versionId());
        if (!verif.conforme()) {
            return refus(documentId, Issue.ECHEC, com.ipt.ged.fichier.CodesErreurFichier.INTEGRITE_COMPROMISE,
                    "empreinte SHA-256 divergente : " + verif.statut());
        }
        CopiesConservation.Production copie = copies.produire(i.versionId(), i.cleFichierId(), i.typeMime(), i.nom());
        return new Preparation(documentId, i.versionId(), i.empreinte(), i.nom(), copie, null);
    }

    private record Instantane(UUID versionId, UUID cleFichierId, String empreinte, String typeMime, String nom) {
    }

    /**
     * Phase 2, dans la transaction de l'appelant : document verrouillé et
     * recontrôlé, copie enregistrée, statut ARCHIVE, événement d'audit.
     */
    /**
     * @param archivisteUtilisateurId identité GED de l'archiviste (contrat {@link ArchivageDocuments}).
     */
    public Resultat appliquer(Preparation p, Acteur acteur, UUID archivisteUtilisateurId, UUID jobId) {
        if (p.refusee()) return new Resultat(p.documentId(), p.refus().issue(), null, p.refus().motif());
        UploadDocument d = documents.findByIdPourEcriture(p.documentId()).orElse(null);
        if (d == null || d.isSupprime()) {
            return new Resultat(p.documentId(), Issue.IGNORE, null, "document mis en corbeille pendant l'archivage");
        }
        if (d.estArchive()) return new Resultat(p.documentId(), Issue.DEJA_ARCHIVE, null, "document déjà archivé");
        if (d.isVerrouille()) return new Resultat(p.documentId(), Issue.IGNORE, null, "document verrouillé");
        UUID courante = courante(d).map(DocumentVersion::getId).orElse(null);
        if (!p.versionId().equals(courante)) {
            return new Resultat(p.documentId(), Issue.ECHEC, null,
                    "nouvelle version versée pendant l'archivage : à relancer");
        }
        UUID par = acteur != null ? acteur.employeId() : null;
        Optional<UUID> remplacee = copies.enregistrer(p.versionId(), p.copie(), par);
        remplacee.ifPresent(this::detruireApresValidation);
        // Statut, date et archiviste (identité GED) posés par le contrat du lot
        // modèle, dans la transaction de l'appelant.
        statuts.archiver(p.documentId(), archivisteUtilisateurId);
        CopiesConservation.Production c = p.copie();
        if (!c.valide()) {
            log.warn("Document {} archivé avec son seul original : copie PDF/A en échec ({})", p.documentId(), c.motif());
        }
        evenements.publishEvent(new DocumentArchive(p.documentId(), p.versionId(), acteur, Instant.now(),
                p.empreinte(), c.statut(), c.valide() ? null : "Copie PDF/A-2 non produite : " + c.motif(), jobId));
        return new Resultat(p.documentId(), c.valide() ? Issue.ARCHIVE : Issue.ANOMALIE, c.statut(),
                c.valide() ? null : c.motif());
    }

    /** Détruit la copie produite par une préparation qui n'a pas été appliquée. */
    public void abandonner(Preparation p) {
        if (p == null || p.copie() == null || !p.copie().valide() || p.copie().reutilisee()) return;
        try {
            stockage.detruire(p.copie().cleFichierId());
        } catch (RuntimeException e) {
            log.warn("Copie de conservation {} non détruite", p.copie().cleFichierId(), e);
        }
    }

    private void detruireApresValidation(UUID fichierId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stockage.detruire(fichierId);
            }
        });
    }

    private static Preparation refus(UUID documentId, Issue issue, String code, String motif) {
        return new Preparation(documentId, null, null, null, null, new Rejet(issue, code, motif));
    }

    static Optional<DocumentVersion> courante(UploadDocument d) {
        return d.getVersions().stream().filter(DocumentVersion::isPrincipale).findFirst()
                .or(() -> d.getVersions().stream().max(Comparator.comparing(DocumentVersion::getId)));
    }
}
