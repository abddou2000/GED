package com.ipt.ged.accessgroup;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessGroupRepository extends JpaRepository<AccessGroup, UUID> {

    /* Le tri n'est plus figé dans le nom de la méthode : un « OrderByIdDesc »
       gagne toujours contre le Sort du Pageable, ce qui rendait les en-têtes de
       colonne cliquables sans effet. */
    Page<AccessGroup> findBySupprimeFalseAndNameContainingIgnoreCase(String search, Pageable pageable);

    Page<AccessGroup> findBySupprimeTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    /** Charge le groupe avec ses membres (pour le détail / l'édition). */
    @EntityGraph(attributePaths = {"users"})
    Optional<AccessGroup> findWithRefsById(UUID id);

    List<AccessGroup> findByIdInAndSupprimeFalse(List<UUID> ids);

    List<AccessGroup> findByIdInAndSupprimeTrue(List<UUID> ids);

    List<AccessGroup> findBySupprimeFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    long countBySupprimeFalse();

    /** Groupes vivants dont la personne est membre (tuile du tableau de bord). */
    @Query("select count(g) from AccessGroup g join g.users m where m.id = :employeId and g.supprime = false")
    long compterPourMembre(@Param("employeId") UUID employeId);
}
