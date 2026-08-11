package com.ipt.ged.index;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexRepository extends JpaRepository<IndexField, Long> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<IndexField> findByDeletedFalseAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    Page<IndexField> findByDeletedTrueAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    List<IndexField> findByIdInAndDeletedFalse(List<Long> ids);

    List<IndexField> findByIdInAndDeletedTrue(List<Long> ids);

    List<IndexField> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
