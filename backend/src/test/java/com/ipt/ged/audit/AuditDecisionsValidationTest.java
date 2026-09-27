package com.ipt.ged.audit;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.signature.WorkflowSignature;
import com.ipt.ged.signature.WorkflowSignatureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Décisions de validation tracées (dossier fonctionnel §4.9.4 : « décision de
 * validation »), avec le document, l'étape et le motif.
 */
class AuditDecisionsValidationTest {

    private final WorkflowSignatureRepository repo = mock(WorkflowSignatureRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SignatureService service = new SignatureService(repo, audit);

    private final UUID documentId = UUID.randomUUID();
    private final UUID employeId = UUID.randomUUID();
    private final UUID signatureId = UUID.randomUUID();

    private WorkflowSignature etapeUnique() {
        UploadDocument doc = new UploadDocument("Facture");
        doc.setId(documentId);
        Employe e = new Employe("Sara", "Bennani", true);
        e.setId(employeId);
        WorkflowSignature s = new WorkflowSignature();
        s.setId(signatureId);
        s.setDocument(doc);
        s.setEmploye(e);
        s.setStepOrder(1);
        when(repo.findById(signatureId)).thenReturn(Optional.of(s));
        when(repo.findByDocumentIdOrderByStepOrderAsc(documentId)).thenReturn(List.of(s));
        return s;
    }

    private EntreeAudit trace() {
        ArgumentCaptor<EvenementAudit> e = ArgumentCaptor.forClass(EvenementAudit.class);
        verify(audit).enregistrer(e.capture());
        return (EntreeAudit) e.getValue();
    }

    @Test
    @DisplayName("Approbation : VALIDATION_APPROUVEE sur le document, avec l'étape et le motif")
    void approbation() {
        etapeUnique();
        service.approve(signatureId, employeId, "conforme");
        EntreeAudit t = trace();
        assertThat(t.action()).isEqualTo("VALIDATION_APPROUVEE");
        assertThat(t.objetType()).isEqualTo("DOCUMENT");
        assertThat(t.objetId()).isEqualTo(documentId);
        assertThat(t.motif()).isEqualTo("conforme");
        assertThat(t.apres()).containsEntry("etape", 1).containsEntry("statut", "SIGNED");
    }

    @Test
    @DisplayName("Rejet : VALIDATION_REJETEE avec le motif")
    void rejet() {
        etapeUnique();
        service.reject(signatureId, employeId, "pièce illisible");
        EntreeAudit t = trace();
        assertThat(t.action()).isEqualTo("VALIDATION_REJETEE");
        assertThat(t.motif()).isEqualTo("pièce illisible");
    }

    @Test
    @DisplayName("Décision refusée (non assigné) : aucune trace de décision")
    void refus() {
        etapeUnique();
        assertThatThrownBy(() -> service.approve(signatureId, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(audit, never()).enregistrer(any());
    }
}
