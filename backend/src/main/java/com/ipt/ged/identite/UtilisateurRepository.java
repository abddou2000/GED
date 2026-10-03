package com.ipt.ged.identite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, UUID> {

    Optional<Utilisateur> findByObjectGuid(UUID objectGuid);

    /** sAMAccountName est insensible à la casse côté annuaire (index sur lower()). */
    @Query("select u from Utilisateur u where lower(u.identifiant) = lower(:identifiant)")
    Optional<Utilisateur> findByIdentifiant(@Param("identifiant") String identifiant);

    Optional<Utilisateur> findByEmployeId(UUID employeId);

    boolean existsByEmployeId(UUID employeId);

    List<Utilisateur> findByEmployeIdIn(Collection<UUID> employeIds);
}
