package com.ipt.ged.signature;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.signature.dto.SignatureResponse;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowStep;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Circuit de signature (machine à états). Les demandes sont créées à l'upload,
 * une par étape ; l'approbation est séquentielle ; un rejet rouvre l'étape
 * précédente. Le document n'est « actif » qu'une fois la dernière étape signée.
 */
@Service
public class SignatureService {

    private final WorkflowSignatureRepository repo;

    /** Journal d'audit : décisions de validation (DAT §7.4.1, dossier fonctionnel §4.9.4). */
    private final AuditService audit;

    /** Point d'application unique des droits (lot E3), injecté sans modifier le constructeur. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ipt.ged.autorisation.ControleAcces controle;

    public SignatureService(WorkflowSignatureRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    private void tracerDecision(ActionAudit action, WorkflowSignature sig, String motif) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("signatureId", sig.getId());
        decision.put("etape", sig.getStepOrder());
        decision.put("statut", sig.getStatus().name());
        audit.enregistrer(EntreeAudit.de(action, "DOCUMENT", sig.getDocument().getId())
                .avecApres(decision).avecMotif(motif));
    }

    /** Génère le circuit d'un document déposé (une signature par étape). */
    @Transactional
    public int createForDocument(UploadDocument doc) {
        WorkflowGed wf = doc.getWorkspace() != null ? doc.getWorkspace().getWorkflow() : null;
        List<WorkflowStep> steps = wf != null ? wf.getSteps() : List.of();
        if (steps.isEmpty()) {
            doc.setActive(true); // aucune validation requise
            return 0;
        }
        for (WorkflowStep step : steps) {
            WorkflowSignature sig = new WorkflowSignature();
            sig.setDocument(doc);
            sig.setEmploye(step.getEmploye());
            sig.setStepLabel(step.getLabel());
            sig.setStepOrder(step.getStepOrder());
            sig.setStatus(SignatureStatus.PENDING);
            repo.save(sig);
        }
        doc.setActive(false); // entre en circuit, en attente de validation
        return steps.size();
    }

    /**
     * Mes signatures réellement actionnables (étape précédente signée) sur des
     * documents vivants.
     *
     * <p>La mise en corbeille d'un document ne touchait pas ses demandes de
     * signature : elles restaient listées et <b>approuvables</b>. Le compteur du
     * tableau de bord, lui, comptait tous les {@code PENDING} de la base — d'où
     * l'écart observé (73 annoncés, 41 traitables). Cette méthode est désormais
     * la <b>seule</b> définition de « signature en attente » : la liste et le
     * compteur en sortent tous les deux, ils ne peuvent plus diverger.
     *
     * <p>Le filtre est préféré à une annulation en base : la corbeille est
     * réversible, et restaurer le document doit rendre le circuit exactement
     * dans l'état où il l'avait laissé.
     */
    @Transactional(readOnly = true)
    public List<SignatureResponse> pending(UUID employeId) {
        return repo.findByEmployeIdAndStatusOrderByStepOrderAsc(employeId, SignatureStatus.PENDING).stream()
                .filter(s -> s.getDocument() != null && !s.getDocument().isSupprime())
                .filter(this::isActionable)
                // Hors périmètre (P5) : ni listée ni comptée.
                .filter(s -> controle == null || controle.documentLisible(s.getDocument().getId()))
                .map(SignatureResponse::from)
                .toList();
    }

    /** Nombre de signatures que cet employé peut réellement traiter — voir {@link #pending}. */
    @Transactional(readOnly = true)
    public long nombreEnAttente(UUID employeId) {
        return pending(employeId).size();
    }

    /** Mon historique (signé / rejeté). */
    @Transactional(readOnly = true)
    public List<SignatureResponse> history(UUID employeId) {
        return repo.findByEmployeIdAndStatusInOrderByIdDesc(
                        employeId, List.of(SignatureStatus.SIGNED, SignatureStatus.REJECTED)).stream()
                .map(SignatureResponse::from)
                .toList();
    }

    /** Circuit complet d'un document (toutes les étapes, dans l'ordre). */
    @Transactional(readOnly = true)
    public List<SignatureResponse> documentCircuit(UUID documentId) {
        if (controle != null) controle.exigerLectureDocument(documentId);
        return repo.findByDocumentIdOrderByStepOrderAsc(documentId).stream()
                .map(SignatureResponse::from)
                .toList();
    }

    /**
     * Approuver une étape : avance le circuit ; la dernière active le document.
     *
     * @param actingEmployeId identité issue du jeton, résolue par le contrôleur.
     */
    @Transactional
    public SignatureResponse approve(UUID id, UUID actingEmployeId, String motif) {
        WorkflowSignature sig = load(id);
        exigerValider(sig);
        checkAssignee(sig, actingEmployeId);
        checkDocumentVivant(sig);
        com.ipt.ged.common.Limites.controler(motif, "motif");
        if (sig.getStatus() != SignatureStatus.PENDING) {
            throw new IllegalArgumentException("Cette signature a déjà été traitée");
        }
        if (!previousStepSigned(sig)) {
            throw new IllegalArgumentException("L'étape précédente n'est pas encore validée");
        }
        sig.setStatus(SignatureStatus.SIGNED);
        sig.setSignedAt(Instant.now());
        sig.setMotif(motif);

        List<WorkflowSignature> all = repo.findByDocumentIdOrderByStepOrderAsc(sig.getDocument().getId());
        int maxOrder = all.stream().mapToInt(WorkflowSignature::getStepOrder).max().orElse(sig.getStepOrder());
        if (sig.getStepOrder() == maxOrder) {
            sig.getDocument().setActive(true); // dernière étape → document validé
        }
        tracerDecision(ActionAudit.VALIDATION_APPROUVEE, sig, motif);
        return SignatureResponse.from(sig);
    }

    /** Rejeter une étape (motif obligatoire) : rouvre l'étape précédente. */
    @Transactional
    public SignatureResponse reject(UUID id, UUID actingEmployeId, String motif) {
        if (motif == null || motif.isBlank()) {
            throw new IllegalArgumentException("Le motif est obligatoire pour un rejet");
        }
        // Le motif est libre et alimente une colonne de 255 caractères : au-delà,
        // la base refusait l'insertion et l'utilisateur recevait un 500 nu.
        com.ipt.ged.common.Limites.controler(motif, "motif");
        WorkflowSignature sig = load(id);
        exigerValider(sig);
        checkAssignee(sig, actingEmployeId);
        checkDocumentVivant(sig);
        if (sig.getStatus() != SignatureStatus.PENDING) {
            throw new IllegalArgumentException("Cette signature a déjà été traitée");
        }
        sig.setStatus(SignatureStatus.REJECTED);
        sig.setMotif(motif);

        // Rouvre l'étape précédente (retour en arrière)
        Optional<WorkflowSignature> prev = repo.findByDocumentIdOrderByStepOrderAsc(sig.getDocument().getId()).stream()
                .filter(x -> x.getStepOrder() < sig.getStepOrder())
                .max(Comparator.comparingInt(WorkflowSignature::getStepOrder));
        prev.ifPresent(p -> {
            p.setStatus(SignatureStatus.PENDING);
            p.setSignedAt(null);
        });
        sig.getDocument().setActive(false);
        tracerDecision(ActionAudit.VALIDATION_REJETEE, sig, motif);
        return SignatureResponse.from(sig);
    }

    /**
     * Relance un circuit arrêté par un rejet.
     *
     * <p><b>Pourquoi cette méthode existe.</b> Un rejet passait l'étape en
     * {@code REJECTED} et rien, nulle part, ne pouvait l'en sortir :
     * {@link #approve} refuse tout ce qui n'est pas {@code PENDING}, l'étape
     * suivante reste bloquée derrière elle, et le document disparaît de la file
     * « à traiter » en restant {@code active = false}. Une facture rejetée une
     * fois était donc perdue — il fallait la redéposer. Un circuit de validation
     * qui ne sait pas encaisser un refus ne remplit pas son office.</p>
     *
     * <p><b>Ce qui est remis à zéro, et ce qui ne l'est pas.</b> Seules les
     * étapes refusées redeviennent à traiter. Les étapes déjà signées le
     * restent : elles portent l'accord d'une personne, on ne l'annule pas dans
     * son dos. Le motif du refus est <b>conservé</b> — c'est la seule trace de
     * ce qui a été reproché, et l'approbateur doit l'avoir sous les yeux quand
     * la pièce lui revient.</p>
     */
    @Transactional
    public List<SignatureResponse> relancer(UUID documentId) {
        if (controle != null) {
            controle.exigerSurDocument(com.ipt.ged.autorisation.CodePermission.MODIFIER, documentId);
        }
        List<WorkflowSignature> circuit = repo.findByDocumentIdOrderByStepOrderAsc(documentId);
        if (circuit.isEmpty()) {
            throw new EntityNotFoundException("Aucun circuit de validation pour le document : " + documentId);
        }
        // Un document en corbeille ne se relance pas : il n'est plus en jeu.
        checkDocumentVivant(circuit.get(0));

        List<WorkflowSignature> refusees = circuit.stream()
                .filter(s -> s.getStatus() == SignatureStatus.REJECTED)
                .toList();
        if (refusees.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ce circuit n'a aucune étape rejetée : il n'y a rien à relancer.");
        }
        for (WorkflowSignature s : refusees) {
            s.setStatus(SignatureStatus.PENDING);
            s.setSignedAt(null);
        }
        // Le document redevient « en cours de validation » tant qu'il n'est pas
        // signé de bout en bout.
        circuit.get(0).getDocument().setActive(false);
        audit.enregistrer(EntreeAudit.de(ActionAudit.VALIDATION_RELANCEE, "DOCUMENT", documentId)
                .avecApres(Map.of("etapesRelancees", refusees.stream().map(WorkflowSignature::getStepOrder).toList())));
        return circuit.stream().map(SignatureResponse::from).toList();
    }

    /* ---------- privé ---------- */

    private WorkflowSignature load(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Signature introuvable : " + id));
    }

    /**
     * Seul l'assigné de l'étape peut la traiter.
     *
     * <p>{@code actingEmployeId} doit impérativement provenir du jeton
     * (principal), jamais d'un champ de requête : comparer l'assigné à une valeur
     * choisie par l'appelant revient à lui demander s'il est autorisé, ce qui
     * permettait de signer au nom d'un collègue.
     */
    private void checkAssignee(WorkflowSignature sig, UUID actingEmployeId) {
        if (actingEmployeId == null || sig.getEmploye() == null
                || !sig.getEmploye().getId().equals(actingEmployeId)) {
            throw new IllegalArgumentException("Cette signature ne vous est pas assignée");
        }
    }

    /**
     * On ne signe pas une pièce mise à la corbeille.
     *
     * <p>Même règle que pour le document lui-même : la fiche reste lisible — le
     * circuit d'un document supprimé garde sa valeur d'historique — mais aucune
     * écriture n'y est acceptée. Approuver l'étape d'un document que plus
     * personne ne voit produirait une validation dont il ne resterait aucune
     * trace exploitable.
     */
    private void checkDocumentVivant(WorkflowSignature sig) {
        if (sig.getDocument() != null && sig.getDocument().isSupprime()) {
            throw new IllegalArgumentException(
                    "Document en corbeille : cette étape ne peut plus être traitée. Restaurez-le d'abord.");
        }
    }

    private boolean isActionable(WorkflowSignature s) {
        return s.getStepOrder() <= 1 || previousStepSigned(s);
    }

    private boolean previousStepSigned(WorkflowSignature s) {
        if (s.getStepOrder() <= 1) return true;
        return repo.findByDocumentIdOrderByStepOrderAsc(s.getDocument().getId()).stream()
                .filter(x -> x.getStepOrder() == s.getStepOrder() - 1)
                .findFirst()
                .map(prev -> prev.getStatus() == SignatureStatus.SIGNED)
                .orElse(true);
    }

    /**
     * Décider d'une étape exige la permission Valider sur le document (lot E3) :
     * 404 s'il est hors périmètre, 403 si l'approbateur désigné ne détient plus
     * ce droit.
     */
    private void exigerValider(WorkflowSignature sig) {
        if (controle != null && sig.getDocument() != null) {
            controle.exigerSurDocument(com.ipt.ged.autorisation.CodePermission.VALIDER, sig.getDocument().getId());
        }
    }
}
