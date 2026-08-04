package com.ipt.ged.planindexation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanIndexationRepository extends JpaRepository<PlanIndexation, Long> {

    Page<PlanIndexation> findByDeletedFalseAndNomDuPlanContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<PlanIndexation> findByDeletedTrueAndNomDuPlanContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    @EntityGraph(attributePaths = {"indices"})
    Optional<PlanIndexation> findWithIndicesById(Long id);

    List<PlanIndexation> findByIdInAndDeletedFalse(List<Long> ids);

    List<PlanIndexation> findByIdInAndDeletedTrue(List<Long> ids);

    List<PlanIndexation> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
