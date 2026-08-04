package com.ipt.ged.typedocument;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TypeDocumentRepository extends JpaRepository<TypeDocument, Long> {

    Page<TypeDocument> findByDeletedFalseAndTypeDeDocumentContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    Page<TypeDocument> findByDeletedTrueAndTypeDeDocumentContainingIgnoreCaseOrderByIdDesc(String search, Pageable pageable);

    List<TypeDocument> findByIdInAndDeletedFalse(List<Long> ids);

    List<TypeDocument> findByIdInAndDeletedTrue(List<Long> ids);

    List<TypeDocument> findByDeletedFalseOrderByIdAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
