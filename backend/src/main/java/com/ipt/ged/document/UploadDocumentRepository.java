package com.ipt.ged.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadDocumentRepository extends JpaRepository<UploadDocument, Long> {

    Page<UploadDocument> findByDeletedFalseAndNameContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<UploadDocument> findByDeletedTrueAndNameContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<UploadDocument> findByDeletedFalseAndWorkspaceIdAndNameContainingIgnoreCaseOrderByIdDesc(
            Long workspaceId, String search, Pageable pageable);

    List<UploadDocument> findByIdInAndDeletedFalse(List<Long> ids);

    /** Tous les documents actifs — base de départ de la recherche par index. */
    List<UploadDocument> findByDeletedFalseOrderByIdDesc();

    List<UploadDocument> findByIdInAndDeletedTrue(List<Long> ids);

    long countByDeletedFalse();
}
