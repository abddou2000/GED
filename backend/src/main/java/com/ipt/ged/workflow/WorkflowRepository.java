package com.ipt.ged.workflow;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<WorkflowGed, UUID> {

    /**
     * Liste active (hors corbeille), filtrée par nom, paginée.
     * L'ordre n'est pas figé dans le nom de la méthode : il est porté par le
     * {@link Pageable}, faute de quoi tout tri demandé serait ignoré.
     */
    Page<WorkflowGed> findByDeletedFalseAndNameContainingIgnoreCase(String name, Pageable pageable);

    /** Corbeille (éléments supprimés), filtrée par nom, paginée. */
    Page<WorkflowGed> findByDeletedTrueAndNameContainingIgnoreCase(String name, Pageable pageable);

    @EntityGraph(attributePaths = {"steps", "steps.employe"})
    Optional<WorkflowGed> findWithStepsById(UUID id);

    List<WorkflowGed> findByIdInAndDeletedFalse(List<UUID> ids);

    List<WorkflowGed> findByIdInAndDeletedTrue(List<UUID> ids);
}
