package com.ipt.ged.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadDocumentRepository extends JpaRepository<UploadDocument, UUID>,
        JpaSpecificationExecutor<UploadDocument> {

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
    Optional<UploadDocument> findByIdPourEcriture(@Param("id") UUID id);

    /*
     * Aucune lecture EN MASSE non filtrée : listes, totaux et agrégats passent
     * par JpaSpecificationExecutor avec la spécification de droits
     * d'AccessPredicate (lot E3, point d'application unique). Un test
     * d'architecture (ArchitectureDroitsTest) refuse toute nouvelle méthode de
     * lecture en masse ici.
     */

    List<UploadDocument> findByIdInAndSupprimeFalse(List<UUID> ids);

    /** Jeu de démonstration (profil dev) uniquement : jamais sur un chemin de requête. */
    List<UploadDocument> findBySupprimeFalseOrderByIdDesc();

    List<UploadDocument> findByIdInAndSupprimeTrue(List<UUID> ids);
}
