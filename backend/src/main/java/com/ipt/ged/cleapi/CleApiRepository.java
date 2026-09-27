package com.ipt.ged.cleapi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CleApiRepository extends JpaRepository<CleApi, UUID> {

    Optional<CleApi> findByIdentifiant(String identifiant);

    List<CleApi> findByApplicationIdOrderByCreeLeDesc(UUID applicationId);
}
