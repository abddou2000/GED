package com.ipt.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRattachementRepository extends JpaRepository<DocumentRattachement, UUID> {

    List<DocumentRattachement> findByDocumentIdOrderByCreeLeAsc(UUID documentId);

    Optional<DocumentRattachement> findByDocumentIdAndNoeudId(UUID documentId, UUID noeudId);

    boolean existsByDocumentIdAndNoeudId(UUID documentId, UUID noeudId);
}
