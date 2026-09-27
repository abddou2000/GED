package com.ipt.ged.notification;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne de la boîte d'envoi (DAT §12.9) : une notification pour un
 * destinataire, visible dans l'application et, sauf préférence contraire,
 * expédiée par e-mail de façon asynchrone.
 *
 * <p>Le titre et le message sont figés à la création : ce qui a été annoncé ne
 * change pas si un modèle est corrigé ensuite.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TypeNotification type;

    @Column(name = "destinataire_id", nullable = false)
    private UUID destinataireId;

    @Column(name = "objet_type", length = 64)
    private String objetType;

    @Column(name = "objet_id")
    private UUID objetId;

    @Column(nullable = false, length = 300)
    private String titre;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(length = 1000)
    private String lien;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "lue_le")
    private Instant lueLe;

    @Enumerated(EnumType.STRING)
    @Column(name = "courriel_etat", nullable = false, length = 12)
    private EtatCourriel courrielEtat;

    @Column(name = "courriel_tentatives", nullable = false)
    private int courrielTentatives;

    @Column(name = "courriel_prochain_essai")
    private Instant courrielProchainEssai;

    @Column(name = "courriel_envoye_le")
    private Instant courrielEnvoyeLe;

    @Column(name = "courriel_erreur", length = 1000)
    private String courrielErreur;
}
