package com.ipt.ged.index;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexRepository extends JpaRepository<IndexField, Long> {

    Page<IndexField> findByDeletedFalseAndNomIndexContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<IndexField> findByDeletedTrueAndNomIndexContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    List<IndexField> findByIdInAndDeletedFalse(List<Long> ids);

    List<IndexField> findByIdInAndDeletedTrue(List<Long> ids);

    List<IndexField> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
