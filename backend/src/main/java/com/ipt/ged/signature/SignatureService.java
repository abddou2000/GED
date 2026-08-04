package com.ipt.ged.signature;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.signature.dto.SignatureResponse;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowStep;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Circuit de signature (machine à états). Les demandes sont créées à l'upload,
 * une par étape ; l'approbation est séquentielle ; un rejet rouvre l'étape
 * précédente. Le document n'est « actif » qu'une fois la dernière étape signée.
 */
@Service
public class SignatureService {

    private final WorkflowSignatureRepository repo;

    public SignatureService(WorkflowSignatureRepository repo) {
        this.repo = repo;
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

    /** Mes signatures réellement actionnables (étape précédente signée). */
    @Transactional(readOnly = true)
    public List<SignatureResponse> pending(Long employeId) {
        return repo.findByEmployeIdAndStatusOrderByStepOrderAsc(employeId, SignatureStatus.PENDING).stream()
                .filter(this::isActionable)
                .map(SignatureResponse::from)
                .toList();
    }

    /** Mon historique (signé / rejeté). */
    @Transactional(readOnly = true)
    public List<SignatureResponse> history(Long employeId) {
        return repo.findByEmployeIdAndStatusInOrderByIdDesc(
                        employeId, List.of(SignatureStatus.SIGNED, SignatureStatus.REJECTED)).stream()
                .map(SignatureResponse::from)
                .toList();
    }

    /** Circuit complet d'un document (toutes les étapes, dans l'ordre). */
    @Transactional(readOnly = true)
    public List<SignatureResponse> documentCircuit(Long documentId) {
        return repo.findByDocumentIdOrderByStepOrderAsc(documentId).stream()
                .map(SignatureResponse::from)
                .toList();
    }

    /** Approuver une étape : avance le circuit ; la dernière active le document. */
    @Transactional
    public SignatureResponse approve(Long id, Long actingEmployeId, String motif) {
        WorkflowSignature sig = load(id);
        checkAssignee(sig, actingEmployeId);
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
        return SignatureResponse.from(sig);
    }

    /** Rejeter une étape (motif obligatoire) : rouvre l'étape précédente. */
    @Transactional
    public SignatureResponse reject(Long id, Long actingEmployeId, String motif) {
        if (motif == null || motif.isBlank()) {
            throw new IllegalArgumentException("Le motif est obligatoire pour un rejet");
        }
        WorkflowSignature sig = load(id);
        checkAssignee(sig, actingEmployeId);
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
        return SignatureResponse.from(sig);
    }

    /* ---------- privé ---------- */

    private WorkflowSignature load(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Signature introuvable : " + id));
    }

    private void checkAssignee(WorkflowSignature sig, Long actingEmployeId) {
        if (actingEmployeId == null || sig.getEmploye() == null
                || !sig.getEmploye().getId().equals(actingEmployeId)) {
            throw new IllegalArgumentException("Cette signature ne vous est pas assignée");
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
}
