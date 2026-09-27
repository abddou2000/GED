package com.ipt.ged.workspace;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkSpaceRepository extends JpaRepository<WorkSpace, UUID>, JpaSpecificationExecutor<WorkSpace> {

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Page<WorkSpace> findBySupprimeFalseAndNameContainingIgnoreCase(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Page<WorkSpace> findBySupprimeTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Optional<WorkSpace> findWithRefsById(UUID id);

    /** Tous les dossiers actifs (pour bâtir l'arbre). */
    @EntityGraph(attributePaths = {"owner", "workflow"})
    List<WorkSpace> findBySupprimeFalseOrderByIdAsc();

    List<WorkSpace> findByIdInAndSupprimeFalse(List<UUID> ids);

    List<WorkSpace> findByIdInAndSupprimeTrue(List<UUID> ids);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    long countByParentIdAndSupprimeFalse(UUID parentId);

    long countBySupprimeFalse();

    List<WorkSpace> findByParentIdAndSupprimeFalse(UUID parentId);

    /** Sous-arborescence d'un nœud (lui exclu), par préfixe du chemin matérialisé. */
    List<WorkSpace> findByCheminStartingWithAndIdNot(String chemin, UUID id);
}
