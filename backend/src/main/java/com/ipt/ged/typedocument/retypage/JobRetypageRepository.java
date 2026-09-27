package com.ipt.ged.typedocument.retypage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRetypageRepository extends JpaRepository<JobRetypage, UUID> {

    List<JobRetypage> findTop50ByOrderByCreeLeDesc();
}
