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

    Page<Etiquette> findBySupprimeFalseAndTagContainingIgnoreCase(String search, Pageable pageable);

    Page<Etiquette> findBySupprimeTrueAndTagContainingIgnoreCase(String search, Pageable pageable);

    List<Etiquette> findByIdInAndSupprimeFalse(List<UUID> ids);

    List<Etiquette> findByIdInAndSupprimeTrue(List<UUID> ids);

    List<Etiquette> findBySupprimeFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
