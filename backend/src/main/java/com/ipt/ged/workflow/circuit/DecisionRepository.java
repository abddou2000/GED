package com.ipt.ged.workflow.circuit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    @Query("select d from Decision d where d.validateur.circuit.id = :circuit order by d.creeLe asc, d.id asc")
    List<Decision> duCircuit(@Param("circuit") UUID circuitId);

    List<Decision> findByAuteurIdOrderByCreeLeDesc(UUID auteurId);
}
