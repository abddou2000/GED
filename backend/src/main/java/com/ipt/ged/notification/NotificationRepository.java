package com.ipt.ged.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByDestinataireId(UUID destinataireId, Pageable page);

    Page<Notification> findByDestinataireIdAndLueLeIsNull(UUID destinataireId, Pageable page);

    long countByDestinataireIdAndLueLeIsNull(UUID destinataireId);

    /** Recherche restreinte au destinataire : la notification d'un autre est « introuvable ». */
    Optional<Notification> findByIdAndDestinataireId(UUID id, UUID destinataireId);

    @Modifying
    @Query("UPDATE Notification n SET n.lueLe = :le WHERE n.destinataireId = :destinataire AND n.lueLe IS NULL")
    int marquerToutesLues(@Param("destinataire") UUID destinataire, @Param("le") Instant le);
}
