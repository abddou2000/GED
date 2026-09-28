package com.ipt.ged.workflow.circuit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CircuitRepository extends JpaRepository<Circuit, UUID> {

    List<Circuit> findByDocumentIdOrderByOuvertLeDesc(UUID documentId);

    /** Le circuit non annulé du document (un au plus, index unique partiel). */
    Optional<Circuit> findFirstByDocumentIdAndStatutNot(UUID documentId, Circuit.Statut statut);

    /** Circuits encore à décider (en cours ou refusés) : sources de la liste « à traiter » et des anomalies. */
    @Query("select c from Circuit c where c.statut in :statuts and c.document.supprime = false")
    List<Circuit> ouverts(@Param("statuts") Collection<Circuit.Statut> statuts);

    /** Circuits en cours, sur des documents vivants, qui sollicitent cet employé nommément. */
    @Query("""
           select count(distinct c) from Circuit c join c.validateurs v
            where c.statut = com.ipt.ged.workflow.circuit.Circuit.Statut.EN_COURS
              and c.document.supprime = false and v.employe.id = :employeId""")
    long enCoursPourEmploye(@Param("employeId") UUID employeId);
}
