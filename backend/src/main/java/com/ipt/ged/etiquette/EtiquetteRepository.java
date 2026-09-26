package com.ipt.ged.etiquette;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EtiquetteRepository extends JpaRepository<Etiquette, UUID> {

    /* Le tri n'est plus figé dans le nom des méthodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-têtes de colonne
       cliquables sans effet. */

    Page<Etiquette> findByDeletedFalseAndTagContainingIgnoreCase(String search, Pageable pageable);

    Page<Etiquette> findByDeletedTrueAndTagContainingIgnoreCase(String search, Pageable pageable);

    List<Etiquette> findByIdInAndDeletedFalse(List<UUID> ids);

    List<Etiquette> findByIdInAndDeletedTrue(List<UUID> ids);

    List<Etiquette> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
