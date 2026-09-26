package com.ipt.ged.signature;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowSignatureRepository extends JpaRepository<WorkflowSignature, UUID> {

    /** Mes signatures dans un état donné (ex. PENDING), triées par étape. */
    List<WorkflowSignature> findByEmployeIdAndStatusOrderByStepOrderAsc(UUID employeId, SignatureStatus status);

    /** Mon historique (signé / rejeté). */
    List<WorkflowSignature> findByEmployeIdAndStatusInOrderByIdDesc(UUID employeId, List<SignatureStatus> statuses);

    /** Toutes les signatures d'un document, dans l'ordre des étapes. */
    List<WorkflowSignature> findByDocumentIdOrderByStepOrderAsc(UUID documentId);

    long countByStatus(SignatureStatus status);
}
