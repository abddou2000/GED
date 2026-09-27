package com.ipt.ged.typedocument;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TypeDocumentRepository extends JpaRepository<TypeDocument, UUID> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<TypeDocument> findBySupprimeFalseAndTypeDeDocumentContainingIgnoreCase(String search, Pageable pageable);

    Page<TypeDocument> findBySupprimeTrueAndTypeDeDocumentContainingIgnoreCase(String search, Pageable pageable);

    List<TypeDocument> findByIdInAndSupprimeFalse(List<UUID> ids);

    List<TypeDocument> findByIdInAndSupprimeTrue(List<UUID> ids);

    List<TypeDocument> findBySupprimeFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
