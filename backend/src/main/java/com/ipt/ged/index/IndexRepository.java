package com.ipt.ged.index;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IndexRepository extends JpaRepository<IndexField, UUID> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<IndexField> findBySupprimeFalseAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    Page<IndexField> findBySupprimeTrueAndNomIndexContainingIgnoreCase(String search, Pageable pageable);

    List<IndexField> findByIdInAndSupprimeFalse(List<UUID> ids);

    List<IndexField> findByIdInAndSupprimeTrue(List<UUID> ids);

    List<IndexField> findBySupprimeFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
