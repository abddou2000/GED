package com.ipt.ged.workflow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<WorkflowGed, Long> {

    /** Liste active (hors corbeille), filtrée par nom, paginée. */
    Page<WorkflowGed> findByDeletedFalseAndNameContainingIgnoreCaseOrderByIdDesc(String name, Pageable pageable);

    /** Corbeille (éléments supprimés), filtrée par nom, paginée. */
    Page<WorkflowGed> findByDeletedTrueAndNameContainingIgnoreCaseOrderByIdDesc(String name, Pageable pageable);

    @EntityGraph(attributePaths = {"steps", "steps.employe"})
    Optional<WorkflowGed> findWithStepsById(Long id);

    List<WorkflowGed> findByIdInAndDeletedFalse(List<Long> ids);

    List<WorkflowGed> findByIdInAndDeletedTrue(List<Long> ids);
}
