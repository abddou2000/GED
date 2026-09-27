package com.ipt.ged.workflow.circuit;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.Attribution;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.DroitsResolus;
import com.ipt.ged.autorisation.HorsPerimetreException;
import com.ipt.ged.autorisation.PermissionRefuseeException;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.admin.ServiceHabilitations;
import com.ipt.ged.autorisation.admin.dto.DemandeHabilitation;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.Limites;
import com.ipt.ged.document.DocumentVersion;
import com.ipt.ged.document.DocumentVersionRepository;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.modele.EvenementModeleDocument;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.RoleRepository;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.notification.DemandeNotification;
import com.ipt.ged.notification.TypeNotification;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowStep;
import com.ipt.ged.workflow.api.ActeurWorkflow;
import com.ipt.ged.workflow.circuit.dto.CircuitResponse;
import com.ipt.ged.workflow.circuit.dto.VuesWorkflow;
import com.ipt.ged.workflow.circuit.evenement.EvenementWorkflow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Workflow de validation (dossier technique §12.8, décisions D7, D1, QR1).
 *
 * <ul>
 *   <li><b>Circuit figé au dépôt</b> : la règle applicable (type, sinon nœud
 *       le plus proche) est COPIÉE dans le circuit ; la modifier ensuite ne
 *       touche que les dépôts futurs.</li>
 *   <li><b>Validateurs parallèles</b> (D7) : tous sollicités en même temps,
 *       aucun ordre, aucun facultatif. Un validateur par rôle est résolu au
 *       moment de la décision : quiconque détient ce rôle sur le périmètre
 *       (et la permission Valider sur le document) peut décider pour lui.</li>
 *   <li><b>Statut recalculé</b> dans la transaction de chaque décision et de
 *       chaque versement : VALIDE si, pour chaque validateur, la dernière
 *       décision non annulée sur la version COURANTE est VALIDE ; REFUSE si au
 *       moins un refus sur la version courante ; EN_COURS sinon. Un versement
 *       rend donc caduques toutes les décisions antérieures.</li>
 *   <li><b>Pas de détection automatique</b> (D1) : un validateur défaillant
 *       est signalé à l'Administrateur, qui le réaffecte à la main, avec
 *       traçabilité.</li>
 * </ul>
 * Toute action est faite au nom d'une personne nommée ({@link ActeurWorkflow}) ;
 * ses droits sont ceux d'{@link AccessPredicate}, même quand une application
 * agit pour elle (D8).
 */
@Service
public class ServiceCircuits {

    static final String NOMME = "NOMME";
    static final String ROLE = "ROLE";
    static final String EN_ATTENTE = "EN_ATTENTE";
    private static final String LECTEUR = "LECTEUR";

    private final CircuitRepository circuits;
    private final DecisionRepository decisions;
    private final UploadDocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final EmployeRepository employes;
    private final UtilisateurRepository utilisateurs;
    private final RoleRepository roles;
    private final HabilitationRepository habilitations;
    private final ServiceHabilitations serviceHabilitations;
    private final AccessPredicate predicat;
    private final ReglesApplicables regles;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher evenements;
    private final Duration inactivite;

    public ServiceCircuits(CircuitRepository circuits, DecisionRepository decisions,
                           UploadDocumentRepository documents, DocumentVersionRepository versions,
                           EmployeRepository employes, UtilisateurRepository utilisateurs, RoleRepository roles,
                           HabilitationRepository habilitations, ServiceHabilitations serviceHabilitations,
                           AccessPredicate predicat, ReglesApplicables regles, JdbcTemplate jdbc,
                           ApplicationEventPublisher evenements,
                           @Value("${ged.workflow.inactivite-jours:90}") int joursInactivite) {
        this.circuits = circuits;
        this.decisions = decisions;
        this.documents = documents;
        this.versions = versions;
        this.employes = employes;
        this.utilisateurs = utilisateurs;
        this.roles = roles;
        this.habilitations = habilitations;
        this.serviceHabilitations = serviceHabilitations;
        this.predicat = predicat;
        this.regles = regles;
        this.jdbc = jdbc;
        this.evenements = evenements;
        this.inactivite = Duration.ofDays(Math.max(1, joursInactivite));
    }

    /* ============================================================ ouverture */

    /**
     * Ouvre le circuit d'un document qui vient d'être déposé, dans la
     * transaction du dépôt. Sans règle applicable (ou règle sans validateur),
     * le document est utilisable d'emblée ; sinon il ne l'est qu'une fois
     * validé.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Circuit> ouvrirAuDepot(UploadDocument doc) {
        Optional<ReglesApplicables.RegleApplicable> r = regles.pour(doc.getTypeDocument(), doc.getWorkspace());
        if (r.isEmpty() || r.get().regle().getSteps().isEmpty()) {
            doc.setActive(true);
            return Optional.empty();
        }
        Circuit c = figer(doc, r.get().regle(), ActeurCourant.utilisateurId());
        publierOuverture(c, EvenementWorkflow.CIRCUIT_OUVERT, null);
        return Optional.of(c);
    }

    /**
     * Ouvre un nouveau circuit à la main (après une annulation) : la règle
     * applicable AUJOURD'HUI est figée. Réservé à qui peut modifier le document.
     */
    @Transactional
    public CircuitResponse ouvrir(UUID documentId, ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        Set<CodePermission> perms = exigerLecture(d, documentId);
        if (!perms.contains(CodePermission.MODIFIER) && !administrateur(d)) {
            throw new PermissionRefuseeException("Permission « MODIFIER » requise pour ouvrir un circuit");
        }
        UploadDocument doc = documents.findByIdPourEcriture(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
        exigerVivant(doc);
        if (circuits.findFirstByDocumentIdAndStatutNot(documentId, Circuit.Statut.ANNULE).isPresent()) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.CIRCUIT_DEJA_OUVERT,
                    "Un circuit de validation est déjà ouvert ou clos pour ce document : annulez-le d'abord.");
        }
        ReglesApplicables.RegleApplicable r = regles.pour(doc.getTypeDocument(), doc.getWorkspace())
                .filter(x -> !x.regle().getSteps().isEmpty())
                .orElseThrow(() -> ErreurWorkflowException.conflit(ErreurWorkflowException.AUCUNE_REGLE,
                        "Aucune règle de workflow ne s'applique à ce document."));
        Circuit c = figer(doc, r.regle(), acteur.utilisateurId());
        publierOuverture(c, EvenementWorkflow.VALIDATION_RELANCEE, acteur);
        return reponse(c, acteur, d);
    }

    /** Copie figée de la règle : les validateurs du circuit ne relisent jamais la règle. */
    private Circuit figer(UploadDocument doc, WorkflowGed regle, UUID initiateur) {
        Circuit c = new Circuit(doc, regle.getId(), initiateur);
        int position = 1;
        for (WorkflowStep s : regle.getSteps()) {
            CircuitValidateur v = new CircuitValidateur();
            v.setEmploye(s.getEmploye());
            v.setRole(s.getEmploye() == null ? s.getRole() : null);
            v.setPerimetreNoeudId(s.getEmploye() == null ? s.getPerimetreNoeudId() : null);
            v.setLibelle(s.getLabel() == null || s.getLabel().isBlank() ? "Validation" : s.getLabel().trim());
            v.setPosition(position++);
            c.ajouter(v);
        }
        doc.setActive(false);
        return circuits.save(c);
    }

    private void publierOuverture(Circuit c, String action, ActeurWorkflow acteur) {
        UploadDocument doc = c.getDocument();
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("documentId", doc.getId());
        apres.put("regleId", c.getRegleWorkflowId());
        apres.put("validateurs", c.getValidateurs().stream().map(CircuitValidateur::getLibelle).toList());
        DemandeNotification n = DemandeNotification.a(TypeNotification.CIRCUIT_OUVERT, destinataires(c),
                "DOCUMENT", doc.getId(), Map.of("document", doc.getName(),
                        "auteur", acteur != null ? acteur.libelle() : nomUtilisateur(c.getInitiateurId(), new HashMap<>())),
                lien(doc));
        publier(action, c.getId(), null, apres, null, acteur, n);
    }

    /* ============================================================ décisions */

    /**
     * Rend une décision. Sans {@code validateurId}, elle vaut pour chaque
     * validateur du circuit que l'acteur incarne (nommé, ou porteur du rôle).
     */
    @Transactional
    public CircuitResponse decider(UUID circuitId, VuesWorkflow.DemandeDecision demande, ActeurWorkflow acteur) {
        Decision.Type type = typeDecision(demande.decision());
        String motif = demande.motif() == null || demande.motif().isBlank() ? null : demande.motif().trim();
        Limites.controler(motif, "motif", 500);
        if (type == Decision.Type.REFUSE && motif == null) {
            throw ErreurWorkflowException.invalide(ErreurWorkflowException.MOTIF_OBLIGATOIRE,
                    "Le motif est obligatoire pour un refus.");
        }
        Circuit c = charger(circuitId);
        UploadDocument doc = c.getDocument();
        DroitsResolus d = droits(acteur);
        Set<CodePermission> perms = exigerLecture(d, doc.getId());
        exigerVivant(doc);
        if (c.getStatut() == Circuit.Statut.ANNULE || c.getStatut() == Circuit.Statut.VALIDE) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.CIRCUIT_CLOS,
                    "Ce circuit est " + (c.getStatut() == Circuit.Statut.ANNULE ? "annulé" : "déjà validé")
                            + " : il n'attend plus de décision.");
        }
        if (!perms.contains(CodePermission.VALIDER)) {
            throw ErreurWorkflowException.interdit(ErreurWorkflowException.PAS_VALIDATEUR,
                    "La permission « VALIDER » sur ce document est requise pour décider.");
        }
        UUID courante = versionCourante(doc.getId());
        Map<UUID, Decision.Type> etats = etats(c, courante);
        List<CircuitValidateur> incarnes = incarnes(c, acteur, d, doc);
        List<CircuitValidateur> cibles;
        if (demande.validateurId() != null) {
            CircuitValidateur v = c.getValidateurs().stream().filter(x -> x.getId().equals(demande.validateurId()))
                    .findFirst()
                    .orElseThrow(() -> new EntityNotFoundException("Validateur introuvable : " + demande.validateurId()));
            if (!incarnes.contains(v)) {
                throw ErreurWorkflowException.interdit(ErreurWorkflowException.PAS_VALIDATEUR,
                        "Vous n'êtes pas ce validateur du circuit.");
            }
            cibles = List.of(v);
        } else {
            cibles = incarnes;
        }
        if (cibles.isEmpty()) {
            throw ErreurWorkflowException.interdit(ErreurWorkflowException.PAS_VALIDATEUR,
                    "Vous n'êtes pas validateur de ce circuit.");
        }
        if (type == Decision.Type.ANNULEE) {
            cibles = cibles.stream().filter(v -> etats.get(v.getId()) != null).toList();
            if (cibles.isEmpty()) {
                throw ErreurWorkflowException.conflit(ErreurWorkflowException.DECISION_INCOHERENTE,
                        "Aucune décision à annuler sur la version courante.");
            }
        }
        for (CircuitValidateur v : cibles) {
            decisions.save(new Decision(v, courante, type, motif, acteur.utilisateurId(), acteur.applicationId()));
        }
        Circuit.Statut avant = c.getStatut();
        recalculer(c);
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("decision", type.name());
        apres.put("validateurs", cibles.stream().map(CircuitValidateur::getLibelle).toList());
        apres.put("versionId", courante);
        apres.put("statut", c.getStatut().name());
        String action = switch (type) {
            case VALIDE -> EvenementWorkflow.VALIDATION_APPROUVEE;
            case REFUSE -> EvenementWorkflow.VALIDATION_REJETEE;
            case ANNULEE -> EvenementWorkflow.DECISION_ANNULEE;
        };
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("document", doc.getName());
        variables.put("decision", libelleDecision(type));
        variables.put("motif", motif);
        variables.put("auteur", acteur.libelle());
        DemandeNotification n = c.getInitiateurId() == null ? null : DemandeNotification.a(
                TypeNotification.CIRCUIT_DECISION, List.of(c.getInitiateurId()), "DOCUMENT", doc.getId(),
                variables, lien(doc));
        publier(action, c.getId(), Map.of("statut", avant.name()), apres, motif, acteur, n);
        return reponse(c, acteur, d);
    }

    /* ============================================================ recalcul */

    /**
     * Recalcule le statut d'un circuit non annulé et l'état « utilisable » du
     * document. Appelé après chaque décision et chaque changement de version
     * courante, dans la même transaction.
     */
    void recalculer(Circuit c) {
        if (c.getStatut() == Circuit.Statut.ANNULE) return;
        Map<UUID, Decision.Type> etats = etats(c, versionCourante(c.getDocument().getId()));
        Circuit.Statut statut;
        if (etats.containsValue(Decision.Type.REFUSE)) {
            statut = Circuit.Statut.REFUSE;
        } else if (!c.getValidateurs().isEmpty()
                && c.getValidateurs().stream().allMatch(v -> etats.get(v.getId()) == Decision.Type.VALIDE)) {
            statut = Circuit.Statut.VALIDE;
        } else {
            statut = Circuit.Statut.EN_COURS;
        }
        if (statut != c.getStatut()) {
            c.setStatut(statut);
            c.setClosLe(statut == Circuit.Statut.VALIDE ? Instant.now() : null);
        }
        c.getDocument().setActive(statut == Circuit.Statut.VALIDE);
    }

    /**
     * Versement ou désignation d'une autre version courante : les décisions
     * antérieures deviennent caduques, le statut est recalculé dans la
     * transaction du versement (l'événement est publié par ServiceVersions,
     * de façon synchrone).
     */
    @EventListener
    public void versionChangee(EvenementModeleDocument e) {
        if (!EvenementModeleDocument.VERSION_AJOUTEE.equals(e.action())
                && !EvenementModeleDocument.VERSION_RESTAUREE.equals(e.action())) {
            return;
        }
        circuits.findFirstByDocumentIdAndStatutNot(e.documentId(), Circuit.Statut.ANNULE).ifPresent(this::recalculer);
    }

    /**
     * État de chaque validateur sur une version : la dernière décision non
     * annulée (une décision ANNULEE efface la précédente). {@code null} = en
     * attente.
     */
    private Map<UUID, Decision.Type> etats(Circuit c, UUID version) {
        Map<UUID, Decision.Type> etats = new HashMap<>();
        for (Decision x : decisions.duCircuit(c.getId())) {
            if (!Objects.equals(x.getVersionId(), version)) continue;
            UUID v = x.getValidateur().getId();
            if (x.getDecision() == Decision.Type.ANNULEE) etats.remove(v);
            else etats.put(v, x.getDecision());
        }
        return etats;
    }

    /* ============================================================ annulation */

    /**
     * Annule un circuit en cours ou refusé : par son initiateur ou par
     * l'Administrateur, motif obligatoire. Les décisions sont conservées ; un
     * nouveau circuit peut ensuite être ouvert. Le document reste non validé.
     */
    @Transactional
    public CircuitResponse annuler(UUID circuitId, String motif, ActeurWorkflow acteur) {
        String m = motif == null || motif.isBlank() ? null : motif.trim();
        if (m == null) {
            throw ErreurWorkflowException.invalide(ErreurWorkflowException.MOTIF_OBLIGATOIRE,
                    "Le motif d'annulation est obligatoire.");
        }
        Limites.controler(m, "motif", 500);
        Circuit c = charger(circuitId);
        DroitsResolus d = droits(acteur);
        exigerLecture(d, c.getDocument().getId());
        exigerVivant(c.getDocument());
        if (c.getStatut() == Circuit.Statut.ANNULE || c.getStatut() == Circuit.Statut.VALIDE) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.CIRCUIT_CLOS,
                    "Seul un circuit en cours ou refusé peut être annulé.");
        }
        if (!acteur.utilisateurId().equals(c.getInitiateurId()) && !administrateur(d)) {
            throw ErreurWorkflowException.interdit(ErreurWorkflowException.NON_AUTORISE,
                    "Seuls l'initiateur du circuit et l'Administrateur peuvent l'annuler.");
        }
        String avant = c.getStatut().name();
        Instant maintenant = Instant.now();
        c.setStatut(Circuit.Statut.ANNULE);
        c.setAnnulePar(acteur.utilisateurId());
        c.setAnnuleLe(maintenant);
        c.setClosLe(maintenant);
        c.setMotifAnnulation(m);
        c.getDocument().setActive(false);
        Set<UUID> dest = new LinkedHashSet<>(destinataires(c));
        if (c.getInitiateurId() != null && !c.getInitiateurId().equals(acteur.utilisateurId())) {
            dest.add(c.getInitiateurId());
        }
        DemandeNotification n = DemandeNotification.a(TypeNotification.CIRCUIT_ANNULE, dest, "DOCUMENT",
                c.getDocument().getId(), Map.of("document", c.getDocument().getName(), "motif", m,
                        "auteur", acteur.libelle()), lien(c.getDocument()));
        publier(EvenementWorkflow.CIRCUIT_ANNULE, c.getId(), Map.of("statut", avant),
                Map.of("statut", Circuit.Statut.ANNULE.name()), m, acteur, n);
        return reponse(c, acteur, d);
    }

    /* ============================================================ réaffectation */

    /**
     * Réaffectation manuelle d'un validateur encore en attente (D1, QR1) :
     * l'Administrateur désigne une autre personne ; l'ancien validateur,
     * l'auteur, l'instant et le motif sont conservés.
     */
    @Transactional
    public CircuitResponse reaffecter(UUID circuitId, UUID validateurId, VuesWorkflow.DemandeReaffectation demande,
                                      ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        if (!administrateur(d)) {
            throw ErreurWorkflowException.interdit(ErreurWorkflowException.NON_AUTORISE,
                    "La réaffectation d'un validateur est réservée à l'Administrateur.");
        }
        String m = demande.motif() == null || demande.motif().isBlank() ? null : demande.motif().trim();
        if (m == null) {
            throw ErreurWorkflowException.invalide(ErreurWorkflowException.MOTIF_OBLIGATOIRE,
                    "Le motif de réaffectation est obligatoire.");
        }
        Limites.controler(m, "motif", 500);
        Circuit c = charger(circuitId);
        exigerVivant(c.getDocument());
        if (c.getStatut() == Circuit.Statut.ANNULE || c.getStatut() == Circuit.Statut.VALIDE) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.CIRCUIT_CLOS,
                    "Ce circuit n'attend plus de décision.");
        }
        CircuitValidateur v = c.getValidateurs().stream().filter(x -> x.getId().equals(validateurId)).findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Validateur introuvable : " + validateurId));
        if (etats(c, versionCourante(c.getDocument().getId())).get(v.getId()) != null) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.VALIDATEUR_DEJA_DECIDE,
                    "Ce validateur a déjà décidé sur la version courante : il n'y a rien à réaffecter.");
        }
        Employe nouveau = employes.findById(demande.employeId())
                .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + demande.employeId()));
        Map<String, Object> avant = new LinkedHashMap<>();
        avant.put("validateur", designation(v));
        v.setReaffecteDeEmployeId(v.getEmploye() != null ? v.getEmploye().getId() : null);
        v.setEmploye(nouveau);
        v.setRole(null);
        v.setPerimetreNoeudId(null);
        v.setReaffectePar(acteur.utilisateurId());
        v.setReaffecteLe(Instant.now());
        v.setMotifReaffectation(m);
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("validateur", designation(v));
        apres.put("validateurId", v.getId());
        List<UUID> dest = utilisateurs.findByEmployeId(nouveau.getId()).map(Utilisateur::getId).stream().toList();
        DemandeNotification n = dest.isEmpty() ? null : DemandeNotification.a(TypeNotification.CIRCUIT_OUVERT, dest,
                "DOCUMENT", c.getDocument().getId(), Map.of("document", c.getDocument().getName(),
                        "auteur", acteur.libelle()), lien(c.getDocument()));
        publier(EvenementWorkflow.VALIDATEUR_REAFFECTE, c.getId(), avant, apres, m, acteur, n);
        return reponse(c, acteur, d);
    }

    /* ============================================================ diffusion */

    /**
     * Diffusion d'un document validé (§12.8) : habilitations de LECTURE posées
     * sur le document pour des personnes ou des groupes, sans copie. Un
     * document soumis à validation ne se diffuse qu'une fois validé.
     */
    @Transactional
    public VuesWorkflow.ResultatDiffusion diffuser(UUID documentId, VuesWorkflow.DemandeDiffusion demande,
                                                   ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        Set<CodePermission> perms = exigerLecture(d, documentId);
        if (!perms.contains(CodePermission.DIFFUSER)) {
            throw new PermissionRefuseeException("Permission « DIFFUSER » requise sur ce document");
        }
        UploadDocument doc = documents.findById(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
        exigerVivant(doc);
        List<Circuit> historique = circuits.findByDocumentIdOrderByOuvertLeDesc(documentId);
        if (!historique.isEmpty()) {
            boolean valide = historique.stream().filter(c -> c.getStatut() != Circuit.Statut.ANNULE).findFirst()
                    .map(c -> c.getStatut() == Circuit.Statut.VALIDE).orElse(false);
            if (!valide) {
                throw ErreurWorkflowException.conflit(ErreurWorkflowException.DOCUMENT_NON_VALIDE,
                        "Seul un document validé peut être diffusé.");
            }
        }
        List<UUID> personnes = demande.utilisateurIds() == null ? List.of() : demande.utilisateurIds();
        List<UUID> groupes = demande.groupeIds() == null ? List.of() : demande.groupeIds();
        if (personnes.isEmpty() && groupes.isEmpty()) {
            throw new IllegalArgumentException("Désignez au moins une personne ou un groupe.");
        }
        Role lecteur = roles.findByCode(LECTEUR)
                .orElseThrow(() -> new IllegalStateException("Rôle LECTEUR absent : changeset 202610021030 non appliqué"));
        int posees = 0;
        for (UUID u : new LinkedHashSet<>(personnes)) {
            posees += poser(TypeSujet.UTILISATEUR, u, lecteur, documentId, acteur);
        }
        for (UUID g : new LinkedHashSet<>(groupes)) {
            posees += poser(TypeSujet.GROUPE, g, lecteur, documentId, acteur);
        }
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("utilisateurs", personnes);
        apres.put("groupes", groupes);
        apres.put("habilitationsPosees", posees);
        publier(EvenementWorkflow.DOCUMENT_DIFFUSE, documentId, "DOCUMENT", null, apres, null, acteur, null);
        return new VuesWorkflow.ResultatDiffusion(posees);
    }

    /** Une lecture déjà accordée n'est pas dupliquée (diffusion idempotente). */
    private int poser(TypeSujet type, UUID sujet, Role lecteur, UUID documentId, ActeurWorkflow acteur) {
        if (habilitations.existe(type, sujet, lecteur.getId(), null, documentId)) return 0;
        serviceHabilitations.attribuer(new DemandeHabilitation(type, sujet, lecteur.getId(), null, documentId, false),
                acteur.utilisateurId());
        return 1;
    }

    /* ============================================================ lectures */

    @Transactional(readOnly = true)
    public List<CircuitResponse> circuitsDuDocument(UUID documentId, ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        exigerLecture(d, documentId);
        return circuits.findByDocumentIdOrderByOuvertLeDesc(documentId).stream()
                .map(c -> reponse(c, acteur, d)).toList();
    }

    @Transactional(readOnly = true)
    public CircuitResponse circuit(UUID circuitId, ActeurWorkflow acteur) {
        Circuit c = charger(circuitId);
        DroitsResolus d = droits(acteur);
        exigerLecture(d, c.getDocument().getId());
        return reponse(c, acteur, d);
    }

    /** Règle qui s'appliquerait aujourd'hui à ce document ; vide si aucune. */
    @Transactional(readOnly = true)
    public Optional<VuesWorkflow.RegleDocument> regleDuDocument(UUID documentId, ActeurWorkflow acteur) {
        exigerLecture(droits(acteur), documentId);
        UploadDocument doc = documents.findById(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
        return regles.pour(doc.getTypeDocument(), doc.getWorkspace())
                .map(r -> new VuesWorkflow.RegleDocument(r.regle().getId(), r.regle().getName(), r.origine().name(),
                        r.origineId()));
    }

    /**
     * Ce que l'acteur a à décider : circuits en cours, sur des documents
     * vivants qu'il peut valider, où un validateur qu'il incarne est encore
     * en attente sur la version courante. La liste et le compteur du tableau
     * de bord sortent de cette seule méthode.
     */
    @Transactional(readOnly = true)
    public List<VuesWorkflow.ATraiter> aTraiter(ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        List<VuesWorkflow.ATraiter> l = new ArrayList<>();
        Map<UUID, String> noms = new HashMap<>();
        for (Circuit c : circuits.ouverts(List.of(Circuit.Statut.EN_COURS))) {
            UploadDocument doc = c.getDocument();
            if (!predicat.permissionsSurDocument(d, doc.getId()).contains(CodePermission.VALIDER)) continue;
            UUID courante = versionCourante(doc.getId());
            Map<UUID, Decision.Type> etats = etats(c, courante);
            for (CircuitValidateur v : incarnes(c, acteur, d, doc)) {
                if (etats.get(v.getId()) != null) continue;
                l.add(new VuesWorkflow.ATraiter(c.getId(), doc.getId(), doc.getName(), v.getId(), v.getLibelle(),
                        v.nomme() ? NOMME : ROLE, c.getOuvertLe(), nomUtilisateur(c.getInitiateurId(), noms),
                        numero(courante)));
            }
        }
        l.sort((a, b) -> b.ouvertLe().compareTo(a.ouvertLe()));
        return l;
    }

    @Transactional(readOnly = true)
    public long nombreATraiter(ActeurWorkflow acteur) {
        return aTraiter(acteur).stream().map(VuesWorkflow.ATraiter::circuitId).distinct().count();
    }

    /** Décisions rendues par l'acteur, sur les documents qu'il peut encore lire. */
    @Transactional(readOnly = true)
    public List<VuesWorkflow.DecisionRendue> historique(ActeurWorkflow acteur) {
        DroitsResolus d = droits(acteur);
        return decisions.findByAuteurIdOrderByCreeLeDesc(acteur.utilisateurId()).stream()
                .filter(x -> predicat.permissionsSurDocument(d, x.getValidateur().getCircuit().getDocument().getId())
                        .contains(CodePermission.CONSULTER))
                .map(x -> {
                    Circuit c = x.getValidateur().getCircuit();
                    return new VuesWorkflow.DecisionRendue(x.getId(), c.getId(), c.getDocument().getId(),
                            c.getDocument().getName(), x.getValidateur().getLibelle(), x.getDecision().name(),
                            x.getMotif(), numero(x.getVersionId()), x.getCreeLe(), c.getStatut().name());
                })
                .toList();
    }

    /**
     * Validateurs qui ne peuvent pas décider (D1 : aucune détection
     * automatique n'agit, l'Administrateur est prévenu et réaffecte).
     */
    @Transactional(readOnly = true)
    public List<VuesWorkflow.Anomalie> anomalies(ActeurWorkflow acteur) {
        if (!administrateur(droits(acteur))) {
            throw ErreurWorkflowException.interdit(ErreurWorkflowException.NON_AUTORISE,
                    "Tableau de bord réservé à l'Administrateur.");
        }
        List<VuesWorkflow.Anomalie> l = new ArrayList<>();
        Instant seuil = Instant.now().minus(inactivite);
        for (Circuit c : circuits.ouverts(List.of(Circuit.Statut.EN_COURS, Circuit.Statut.REFUSE))) {
            UploadDocument doc = c.getDocument();
            Map<UUID, Decision.Type> etats = etats(c, versionCourante(doc.getId()));
            for (CircuitValidateur v : c.getValidateurs()) {
                if (etats.get(v.getId()) != null) continue;
                String anomalie = anomalie(v, doc, seuil);
                if (anomalie == null) continue;
                l.add(new VuesWorkflow.Anomalie(c.getId(), doc.getId(), doc.getName(), v.getId(), v.getLibelle(),
                        v.getEmploye() != null ? v.getEmploye().getId() : null,
                        v.getEmploye() != null ? v.getEmploye().getFullName() : null,
                        v.getRole() != null ? v.getRole().getCode() : null, anomalie, c.getOuvertLe()));
            }
        }
        return l;
    }

    private String anomalie(CircuitValidateur v, UploadDocument doc, Instant seuil) {
        if (!v.nomme()) {
            return porteurs(v, doc).isEmpty() ? "AUCUN_PORTEUR" : null;
        }
        Optional<Utilisateur> u = utilisateurs.findByEmployeId(v.getEmploye().getId());
        if (u.isEmpty()) return "SANS_IDENTITE";
        DroitsResolus d = predicat.droits(sujet(u.get()));
        if (!predicat.permissionsSurDocument(d, doc.getId()).contains(CodePermission.VALIDER)) return "SANS_DROIT";
        Instant derniere = u.get().getDerniereConnexionLe();
        return derniere == null || derniere.isBefore(seuil) ? "INACTIF" : null;
    }

    /* ============================================================ résolution des validateurs */

    /** Validateurs du circuit que l'acteur incarne (droit Valider vérifié à part). */
    private List<CircuitValidateur> incarnes(Circuit c, ActeurWorkflow acteur, DroitsResolus d, UploadDocument doc) {
        List<CircuitValidateur> l = new ArrayList<>();
        for (CircuitValidateur v : c.getValidateurs()) {
            if (v.nomme()) {
                if (acteur.employeId() != null && acteur.employeId().equals(v.getEmploye().getId())) l.add(v);
            } else if (v.getRole() != null && detientRole(d, v, doc)) {
                l.add(v);
            }
        }
        return l;
    }

    /**
     * Le sujet détient-il le rôle du validateur sur son périmètre ? Globalement,
     * sur le périmètre ou l'un de ses ancêtres (héritage), ou sur le document
     * lui-même.
     */
    private boolean detientRole(DroitsResolus d, CircuitValidateur v, UploadDocument doc) {
        Set<UUID> portee = portee(v, doc);
        for (Attribution a : d.attributions()) {
            if (a.roleId() == null || !a.roleId().equals(v.getRole().getId())) continue;
            if (a.globale() || (a.noeudId() != null && portee.contains(a.noeudId()))
                    || doc.getId().equals(a.documentId())) {
                return true;
            }
        }
        return false;
    }

    /** Périmètre du validateur par rôle (défaut : l'emplacement principal) et ses ancêtres. */
    private Set<UUID> portee(CircuitValidateur v, UploadDocument doc) {
        UUID perimetre = v.getPerimetreNoeudId() != null ? v.getPerimetreNoeudId() : doc.getWorkspace().getId();
        Set<UUID> portee = new HashSet<>();
        portee.add(perimetre);
        var n = predicat.arbre().noeud(perimetre);
        if (n != null) portee.addAll(n.ancetres());
        return portee;
    }

    /**
     * Personnes qui peuvent décider pour un validateur par rôle : porteurs du
     * rôle (en propre ou par un groupe GED vivant) sur la portée, et qui ont
     * la permission Valider sur le document.
     */
    private List<UUID> porteurs(CircuitValidateur v, UploadDocument doc) {
        Set<UUID> portee = portee(v, doc);
        List<Object> args = new ArrayList<>();
        args.add(v.getRole().getId());
        args.addAll(portee);
        args.add(doc.getId());
        String marques = String.join(", ", java.util.Collections.nCopies(portee.size(), "?"));
        List<UUID> ids = jdbc.queryForList("""
                SELECT DISTINCT u.id FROM habilitation h
                  JOIN utilisateur u ON u.id = h.utilisateur_id
                    OR (h.sujet_type = 'GROUPE' AND u.employe_id IN (
                        SELECT m.employe_id FROM groupe_membre m JOIN groupe_ged g ON g.id = m.groupe_ged_id
                         WHERE g.id = h.groupe_ged_id AND g.supprime = false))
                 WHERE h.role_id = ?
                   AND ((h.noeud_id IS NULL AND h.document_id IS NULL) OR h.noeud_id IN (%s) OR h.document_id = ?)"""
                .formatted(marques), UUID.class, args.toArray());
        List<Utilisateur> candidats = utilisateurs.findAllById(ids);
        return candidats.stream()
                .filter(u -> predicat.permissionsSurDocument(predicat.droits(sujet(u)), doc.getId())
                        .contains(CodePermission.VALIDER))
                .map(Utilisateur::getId).toList();
    }

    /** Identités GED à prévenir : validateurs nommés connus de la GED et porteurs des rôles. */
    private List<UUID> destinataires(Circuit c) {
        Set<UUID> l = new LinkedHashSet<>();
        for (CircuitValidateur v : c.getValidateurs()) {
            if (v.nomme()) {
                utilisateurs.findByEmployeId(v.getEmploye().getId()).ifPresent(u -> l.add(u.getId()));
            } else if (v.getRole() != null) {
                l.addAll(porteurs(v, c.getDocument()));
            }
        }
        return new ArrayList<>(l);
    }

    /**
     * Refuse de forcer l'état « utilisable » d'un document qui a un circuit :
     * seul le workflow le rend utilisable (validation) ou non.
     */
    public void exigerHorsCircuit(UUID documentId) {
        if (!circuits.findByDocumentIdOrderByOuvertLeDesc(documentId).isEmpty()) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.DOCUMENT_NON_VALIDE,
                    "Ce document est soumis à un circuit de validation : son état dépend des décisions.");
        }
    }

    /** Emplacement principal d'un document : nœud contrôlé par la portée d'une clé d'API (D8). */
    public UUID noeudDuDocument(UUID documentId) {
        return jdbc.queryForList("SELECT noeud_principal_id FROM document WHERE id = ?", UUID.class, documentId)
                .stream().findFirst().orElse(null);
    }

    public UUID noeudDuCircuit(UUID circuitId) {
        return jdbc.queryForList("""
                SELECT d.noeud_principal_id FROM circuit c JOIN document d ON d.id = c.document_id
                 WHERE c.id = ?""", UUID.class, circuitId).stream().findFirst().orElse(null);
    }

    /* ============================================================ outils */

    private DroitsResolus droits(ActeurWorkflow acteur) {
        if (acteur == null || acteur.utilisateurId() == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Opération de workflow réservée à une personne nommée");
        }
        return predicat.droits(new Sujet(TypeSujet.UTILISATEUR, acteur.utilisateurId(), acteur.employeId(),
                acteur.libelle()));
    }

    private static Sujet sujet(Utilisateur u) {
        return new Sujet(TypeSujet.UTILISATEUR, u.getId(), u.getEmploye() != null ? u.getEmploye().getId() : null,
                u.getIdentifiant());
    }

    /** L'Administrateur du workflow : celui qui gère les référentiels (règles comprises). */
    private static boolean administrateur(DroitsResolus d) {
        return d.administre(CodePermission.GERER_REFERENTIELS);
    }

    private Set<CodePermission> exigerLecture(DroitsResolus d, UUID documentId) {
        Set<CodePermission> perms = predicat.permissionsSurDocument(d, documentId);
        if (!perms.contains(CodePermission.CONSULTER)) {
            throw new HorsPerimetreException("Document introuvable : " + documentId);
        }
        return perms;
    }

    private static void exigerVivant(UploadDocument doc) {
        if (doc.isSupprime()) {
            throw ErreurWorkflowException.conflit(ErreurWorkflowException.DOCUMENT_EN_CORBEILLE,
                    "Document en corbeille : restaurez-le d'abord.");
        }
    }

    private Circuit charger(UUID id) {
        return circuits.findById(id).orElseThrow(() -> new EntityNotFoundException("Circuit introuvable : " + id));
    }

    private static Decision.Type typeDecision(String s) {
        try {
            return Decision.Type.valueOf(s == null ? "" : s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Décision inconnue : attendu VALIDE, REFUSE ou ANNULEE");
        }
    }

    private static String libelleDecision(Decision.Type t) {
        return switch (t) {
            case VALIDE -> "validé";
            case REFUSE -> "refusé";
            case ANNULEE -> "retiré sa décision";
        };
    }

    private UUID versionCourante(UUID documentId) {
        return versions.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId).stream().findFirst()
                .map(DocumentVersion::getId).orElse(null);
    }

    private Integer numero(UUID versionId) {
        return versionId == null ? null : versions.findById(versionId).map(DocumentVersion::getNumero).orElse(null);
    }

    private String nomUtilisateur(UUID utilisateurId, Map<UUID, String> cache) {
        if (utilisateurId == null) return null;
        return cache.computeIfAbsent(utilisateurId, id -> utilisateurs.findById(id)
                .map(u -> u.getEmploye() != null ? u.getEmploye().getFullName() : u.getIdentifiant())
                .orElse(null));
    }

    private static String designation(CircuitValidateur v) {
        if (v.nomme()) return v.getLibelle() + " : " + v.getEmploye().getFullName();
        return v.getLibelle() + " : rôle " + (v.getRole() != null ? v.getRole().getCode() : "?");
    }

    private static String lien(UploadDocument doc) {
        return "documents/" + doc.getId();
    }

    private void publier(String action, UUID circuitId, Map<String, Object> avant, Map<String, Object> apres,
                         String motif, ActeurWorkflow acteur, DemandeNotification n) {
        publier(action, circuitId, "CIRCUIT", avant, apres, motif, acteur, n);
    }

    private void publier(String action, UUID objetId, String objetType, Map<String, Object> avant,
                         Map<String, Object> apres, String motif, ActeurWorkflow acteur, DemandeNotification n) {
        evenements.publishEvent(new EvenementWorkflow(action, objetId, objetType, avant, apres, motif,
                acteur != null ? acteur.utilisateurId() : null, acteur != null ? acteur.applicationId() : null,
                acteur != null ? acteur.libelle() : null, n, Instant.now()));
    }

    /* ============================================================ vue */

    CircuitResponse reponse(Circuit c, ActeurWorkflow acteur, DroitsResolus d) {
        UploadDocument doc = c.getDocument();
        UUID courante = versionCourante(doc.getId());
        Map<UUID, Integer> numeros = new HashMap<>();
        for (DocumentVersion v : versions.findByDocumentIdOrderByIdDesc(doc.getId())) {
            numeros.put(v.getId(), v.getNumero());
        }
        List<Decision> toutes = decisions.duCircuit(c.getId());
        Map<UUID, Decision.Type> etats = etats(c, courante);
        Map<UUID, Instant> dernieres = new HashMap<>();
        for (Decision x : toutes) {
            if (Objects.equals(x.getVersionId(), courante)) dernieres.put(x.getValidateur().getId(), x.getCreeLe());
        }
        Map<UUID, String> noms = new HashMap<>();
        List<CircuitResponse.Validateur> validateurs = c.getValidateurs().stream().map(v -> {
            Decision.Type e = etats.get(v.getId());
            String reaffecteDe = v.getReaffecteDeEmployeId() == null ? null
                    : employes.findById(v.getReaffecteDeEmployeId()).map(Employe::getFullName).orElse(null);
            return new CircuitResponse.Validateur(v.getId(), v.nomme() ? NOMME : ROLE,
                    v.getEmploye() != null ? v.getEmploye().getId() : null,
                    v.getEmploye() != null ? v.getEmploye().getFullName() : null,
                    v.getRole() != null ? v.getRole().getCode() : null, v.getPerimetreNoeudId(), v.getLibelle(),
                    e == null ? EN_ATTENTE : e.name(), dernieres.get(v.getId()), reaffecteDe,
                    nomUtilisateur(v.getReaffectePar(), noms), v.getReaffecteLe(), v.getMotifReaffectation());
        }).toList();
        List<CircuitResponse.DecisionVue> vues = toutes.stream().map(x -> new CircuitResponse.DecisionVue(x.getId(),
                x.getValidateur().getId(), x.getVersionId(), numeros.get(x.getVersionId()), x.getDecision().name(),
                x.getMotif(), nomUtilisateur(x.getAuteurId(), noms), x.getApplicationId(), x.getCreeLe(),
                !Objects.equals(x.getVersionId(), courante))).toList();
        boolean ouvert = !doc.isSupprime()
                && (c.getStatut() == Circuit.Statut.EN_COURS || c.getStatut() == Circuit.Statut.REFUSE);
        boolean peutDecider = ouvert
                && predicat.permissionsSurDocument(d, doc.getId()).contains(CodePermission.VALIDER)
                && !incarnes(c, acteur, d, doc).isEmpty();
        boolean peutAnnuler = ouvert && (acteur.utilisateurId().equals(c.getInitiateurId()) || administrateur(d));
        String regle = c.getRegleWorkflowId() == null ? null
                : jdbc.query("SELECT name FROM regle_workflow WHERE id = ?", (rs, i) -> rs.getString(1),
                        c.getRegleWorkflowId()).stream().findFirst().orElse(null);
        return new CircuitResponse(c.getId(), doc.getId(), doc.getName(), c.getStatut().name(), c.getRegleWorkflowId(),
                regle, nomUtilisateur(c.getInitiateurId(), noms), c.getOuvertLe(), c.getClosLe(),
                nomUtilisateur(c.getAnnulePar(), noms), c.getAnnuleLe(), c.getMotifAnnulation(), courante,
                numeros.get(courante), validateurs, vues, peutDecider, peutAnnuler);
    }
}
