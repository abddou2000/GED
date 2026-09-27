package com.ipt.ged.cleapi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    boolean existsByCode(String code);

    List<Application> findAllByOrderByCodeAsc();
}
