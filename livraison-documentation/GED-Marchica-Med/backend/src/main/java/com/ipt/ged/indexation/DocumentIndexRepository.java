package com.ipt.ged.indexation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentIndexRepository extends JpaRepository<DocumentIndex, Long> {

    /** Toutes les valeurs portées par un document (pour l'affichage / l'édition). */
    List<DocumentIndex> findByDocumentIdOrderByIdAsc(Long documentId);

    /** Toutes les valeurs saisies pour un index donné — base du filtrage de recherche. */
    List<DocumentIndex> findByIndexFieldId(Long indexFieldId);

    /** Valeurs de plusieurs documents en une requête (évite le N+1 sur les résultats). */
    @Query("select v from DocumentIndex v join fetch v.indexField where v.document.id in :ids")
    List<DocumentIndex> findByDocumentIds(List<Long> ids);

    /** Identifiants des documents portant au moins une valeur d'index. */
    @Query("select distinct v.document.id from DocumentIndex v")
    List<Long> findDocumentIdsIndexes();

    @Transactional
    void deleteByDocumentId(Long documentId);
}
