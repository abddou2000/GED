package com.ipt.ged.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UploadDocumentRepository extends JpaRepository<UploadDocument, Long> {

    /**
     * Charge un document en prenant un verrou d'écriture sur sa ligne.
     *
     * <p>Sert aux opérations qui déplacent le drapeau « version principale ».
     * Sans verrou, deux dépôts simultanés lisaient tous les deux la même version
     * courante, la démotaient chacun de leur côté puis en promouvaient une : le
     * document se retrouvait avec <b>deux</b> principales, état qu'aucune
     * requête ne savait plus lire. Le verrou porte sur le document — et non sur
     * les versions — parce que c'est lui qui est l'unité de cohérence : les
     * lignes de version en conflit n'existent pas encore au moment où il
     * faudrait les verrouiller.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from UploadDocument d where d.id = :id")
    Optional<UploadDocument> findByIdPourEcriture(@Param("id") Long id);

    /* Le tri n'est plus fige dans le nom des methodes : un « OrderByIdDesc »
       l'emporte sur le Sort du Pageable, ce qui rendait les en-tetes de colonne
       cliquables sans effet. */

    Page<UploadDocument> findByDeletedFalseAndNameContainingIgnoreCase(String search, Pageable pageable);

    Page<UploadDocument> findByDeletedTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    Page<UploadDocument> findByDeletedFalseAndWorkspaceIdAndNameContainingIgnoreCase(
            Long workspaceId, String search, Pageable pageable);

    List<UploadDocument> findByIdInAndDeletedFalse(List<Long> ids);

    /** Tous les documents actifs — base de départ de la recherche par index. */
    List<UploadDocument> findByDeletedFalseOrderByIdDesc();

    List<UploadDocument> findByIdInAndDeletedTrue(List<Long> ids);

    long countByDeletedFalse();

    /** Une ligne de la répartition par type : le libellé du type et son total. */
    interface PartParType {
        String getLabel();
        long getTotal();
    }

    /**
     * Répartition des documents vivants par type, la plus fournie d'abord.
     * Jointure externe : les documents sans type doivent rester comptés, sinon
     * la somme des parts ne retombe pas sur le nombre total de documents.
     */
    @Query("""
           select t.typeDeDocument as label, count(d) as total
             from UploadDocument d
             left join d.typeDocument t
            where d.deleted = false
            group by t.typeDeDocument
            order by count(d) desc""")
    List<PartParType> compterParType();

    /**
     * Dates de création des documents vivants déposés depuis une date. Seule la
     * colonne d'audit est chargée : agréger par jour ne demande pas de
     * rapatrier les entités.
     */
    @Query("""
           select d.createdAt
             from UploadDocument d
            where d.deleted = false and d.createdAt >= :depuis""")
    List<Instant> datesDeCreationDepuis(@Param("depuis") Instant depuis);
}
