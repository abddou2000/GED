package com.ipt.ged.etiquette;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EtiquetteRepository extends JpaRepository<Etiquette, Long> {

    Page<Etiquette> findByDeletedFalseAndTagContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<Etiquette> findByDeletedTrueAndTagContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    List<Etiquette> findByIdInAndDeletedFalse(List<Long> ids);

    List<Etiquette> findByIdInAndDeletedTrue(List<Long> ids);

    List<Etiquette> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
