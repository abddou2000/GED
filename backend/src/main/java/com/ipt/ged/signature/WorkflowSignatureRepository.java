package com.ipt.ged.signature;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowSignatureRepository extends JpaRepository<WorkflowSignature, Long> {

    /** Mes signatures dans un état donné (ex. PENDING), triées par étape. */
    List<WorkflowSignature> findByEmployeIdAndStatusOrderByStepOrderAsc(Long employeId, SignatureStatus status);

    /** Mon historique (signé / rejeté). */
    List<WorkflowSignature> findByEmployeIdAndStatusInOrderByIdDesc(Long employeId, List<SignatureStatus> statuses);

    /** Toutes les signatures d'un document, dans l'ordre des étapes. */
    List<WorkflowSignature> findByDocumentIdOrderByStepOrderAsc(Long documentId);

    long countByStatus(SignatureStatus status);
}
