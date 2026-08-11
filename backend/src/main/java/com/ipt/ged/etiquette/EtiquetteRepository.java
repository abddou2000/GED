package com.ipt.ged.etiquette;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EtiquetteRepository extends JpaRepository<Etiquette, Long> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<Etiquette> findByDeletedFalseAndTagContainingIgnoreCase(String search, Pageable pageable);

    Page<Etiquette> findByDeletedTrueAndTagContainingIgnoreCase(String search, Pageable pageable);

    List<Etiquette> findByIdInAndDeletedFalse(List<Long> ids);

    List<Etiquette> findByIdInAndDeletedTrue(List<Long> ids);

    List<Etiquette> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
