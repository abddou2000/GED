package com.ipt.ged.planindexation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanIndexationRepository extends JpaRepository<PlanIndexation, UUID> {

    Page<PlanIndexation> findByDeletedFalseAndNomDuPlanContainingIgnoreCase(String search, Pageable pageable);

    Page<PlanIndexation> findByDeletedTrueAndNomDuPlanContainingIgnoreCase(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"indices"})
    Optional<PlanIndexation> findWithIndicesById(UUID id);

    List<PlanIndexation> findByIdInAndDeletedFalse(List<UUID> ids);

    List<PlanIndexation> findByIdInAndDeletedTrue(List<UUID> ids);

    List<PlanIndexation> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, UUID id);
}
