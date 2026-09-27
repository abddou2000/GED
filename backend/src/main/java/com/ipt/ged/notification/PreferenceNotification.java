package com.ipt.ged.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Préférence de notification d'un utilisateur (DAT §12.9). Absence de ligne =
 * e-mail actif : la valeur par défaut ne demande aucune écriture.
 */
@Entity
@Table(name = "preference_notification")
@Getter
@Setter
@NoArgsConstructor
public class PreferenceNotification {

    @Id
    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    @Column(name = "courriel_actif", nullable = false)
    private boolean courrielActif = true;

    @Column(name = "modifie_le", nullable = false)
    private Instant modifieLe;

    public PreferenceNotification(UUID utilisateurId) {
        this.utilisateurId = utilisateurId;
    }
}
