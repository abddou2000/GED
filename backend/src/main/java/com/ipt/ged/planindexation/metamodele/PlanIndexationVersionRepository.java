package com.ipt.ged.planindexation.metamodele;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanIndexationVersionRepository extends JpaRepository<PlanIndexationVersion, UUID> {

    Optional<PlanIndexationVersion> findFirstByPlanIndexationIdOrderByNumeroDesc(UUID planIndexationId);

    List<PlanIndexationVersion> findByPlanIndexationIdOrderByNumeroAsc(UUID planIndexationId);
}
