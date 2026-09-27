package com.ipt.ged.cleapi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PorteeCleApiRepository extends JpaRepository<PorteeCleApi, UUID> {

    List<PorteeCleApi> findByCleApiId(UUID cleApiId);
}
