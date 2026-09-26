package com.ipt.ged.typedocument;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TypeDocumentRepository extends JpaRepository<TypeDocument, UUID> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<TypeDocument> findByDeletedFalseAndTypeDeDocumentContainingIgnoreCase(String search, Pageable pageable);

    Page<TypeDocument> findByDeletedTrueAndTypeDeDocumentContainingIgnoreCase(String search, Pageable pageable);

    List<TypeDocument> findByIdInAndDeletedFalse(List<UUID> ids);

    List<TypeDocument> findByIdInAndDeletedTrue(List<UUID> ids);

    List<TypeDocument> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
