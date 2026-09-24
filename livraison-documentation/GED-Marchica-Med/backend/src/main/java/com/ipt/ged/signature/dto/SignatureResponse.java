package com.ipt.ged.signature.dto;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.signature.WorkflowSignature;

/**
 * Données renvoyées au frontend pour une demande de signature.
 */
public record SignatureResponse(
        Long id,
        Ref document,
        String type,
        String workspace,
        String approver,
        String stepLabel,
        int stepOrder,
        String status,
        String signedAt,
        String motif,
        boolean documentActive
) {
    public record Ref(Long id, String label) {}

    public static SignatureResponse from(WorkflowSignature s) {
        UploadDocument d = s.getDocument();
        return new SignatureResponse(
                s.getId(),
                d != null ? new Ref(d.getId(), d.getName()) : null,
                d != null && d.getTypeDocument() != null ? d.getTypeDocument().getTypeDeDocument() : null,
                d != null && d.getWorkspace() != null ? d.getWorkspace().getName() : null,
                s.getEmploye() != null ? s.getEmploye().getFullName() : null,
                s.getStepLabel(), s.getStepOrder(),
                s.getStatus().name(),
                s.getSignedAt() != null ? s.getSignedAt().toString() : null,
                s.getMotif(),
                d != null && d.isActive());
    }
}
