package com.ipt.ged.indexation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface DocumentIndexRepository extends JpaRepository<DocumentIndex, UUID> {

    /** Toutes les valeurs portées par un document (pour l'affichage / l'édition). */
    List<DocumentIndex> findByDocumentIdOrderByIdAsc(UUID documentId);

    /** Toutes les valeurs saisies pour un index donné — base du filtrage de recherche. */
    List<DocumentIndex> findByIndexFieldId(UUID indexFieldId);

    /** Valeurs de plusieurs documents en une requête (évite le N+1 sur les résultats). */
    @Query("select v from DocumentIndex v join fetch v.indexField where v.document.id in :ids")
    List<DocumentIndex> findByDocumentIds(List<UUID> ids);

    /** Identifiants des documents portant au moins une valeur d'index. */
    @Query("select distinct v.document.id from DocumentIndex v")
    List<UUID> findDocumentIdsIndexes();

    @Transactional
    void deleteByDocumentId(UUID documentId);
}
