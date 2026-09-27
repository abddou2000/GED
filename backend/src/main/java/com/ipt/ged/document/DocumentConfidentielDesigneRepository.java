package com.ipt.ged.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentConfidentielDesigneRepository extends JpaRepository<DocumentConfidentielDesigne, UUID> {

    List<DocumentConfidentielDesigne> findByDocumentIdOrderByCreeLeAsc(UUID documentId);

    Optional<DocumentConfidentielDesigne> findByDocumentIdAndUtilisateurId(UUID documentId, UUID utilisateurId);

    boolean existsByDocumentIdAndUtilisateurId(UUID documentId, UUID utilisateurId);
}
