package com.ipt.ged.index;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexRepository extends JpaRepository<IndexField, UUID> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<IndexField> findByDeletedFalseAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    Page<IndexField> findByDeletedTrueAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    List<IndexField> findByIdInAndDeletedFalse(List<UUID> ids);

    List<IndexField> findByIdInAndDeletedTrue(List<UUID> ids);

    List<IndexField> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
