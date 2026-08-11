package com.ipt.ged.accessgroup;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccessGroupRepository extends JpaRepository<AccessGroup, Long> {

    /* Le tri n'est plus figé dans le nom de la méthode : un « OrderByIdDesc »
       gagne toujours contre le Sort du Pageable, ce qui rendait les en-têtes de
       colonne cliquables sans effet. */
    Page<AccessGroup> findByDeletedFalseAndNameContainingIgnoreCase(String search, Pageable pageable);

    Page<AccessGroup> findByDeletedTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    /** Charge le groupe avec ses workspaces et ses membres (pour le détail / l'édition). */
    @EntityGraph(attributePaths = {"workspaces", "users"})
    Optional<AccessGroup> findWithRefsById(Long id);

    List<AccessGroup> findByIdInAndDeletedFalse(List<Long> ids);

    List<AccessGroup> findByIdInAndDeletedTrue(List<Long> ids);

    List<AccessGroup> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    long countByDeletedFalse();
}
