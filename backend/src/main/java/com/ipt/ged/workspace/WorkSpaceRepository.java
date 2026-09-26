package com.ipt.ged.workspace;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkSpaceRepository extends JpaRepository<WorkSpace, UUID> {

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Page<WorkSpace> findByDeletedFalseAndNameContainingIgnoreCase(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Page<WorkSpace> findByDeletedTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "parent", "workflow"})
    Optional<WorkSpace> findWithRefsById(UUID id);

    /** Tous les dossiers actifs (pour bâtir l'arbre). */
    @EntityGraph(attributePaths = {"owner", "workflow"})
    List<WorkSpace> findByDeletedFalseOrderByIdAsc();

    List<WorkSpace> findByIdInAndDeletedFalse(List<UUID> ids);

    List<WorkSpace> findByIdInAndDeletedTrue(List<UUID> ids);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);

    long countByParentIdAndDeletedFalse(UUID parentId);

    long countByDeletedFalse();

    List<WorkSpace> findByParentIdAndDeletedFalse(UUID parentId);
}
