package com.ipt.ged.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <b>API interne des notifications</b> (DAT §12.9) : écrit une notification par
 * destinataire dans la boîte d'envoi.
 *
 * <p>Appelée dans la transaction de l'événement déclencheur (propagation
 * {@code REQUIRED}) : si l'opération est annulée, la notification l'est aussi ;
 * si elle est validée, la notification existe forcément. L'e-mail n'est pas
 * envoyé ici — un relais SMTP lent ou absent ne doit ni ralentir ni faire
 * échouer l'opération métier — mais par {@link ExpediteurCourriels}, après la
 * validation de la transaction, avec trois tentatives.
 *
 * <p>Seuls les cas de {@link TypeNotification} existent (trois familles).
 */
@Service
public class Notifications {

    private static final Logger journal = LoggerFactory.getLogger(Notifications.class);

    /** Publié après l'écriture ; l'expéditeur l'écoute à la validation de la transaction. */
    record NotificationsEcrites(List<UUID> ids) {}

    private final NotificationRepository notifications;
    private final PreferenceNotificationRepository preferences;
    private final AnnuaireDestinataires annuaire;
    private final ModelesNotification modeles;
    private final ApplicationEventPublisher evenements;
    private final Clock horloge;

    @Autowired
    public Notifications(NotificationRepository notifications, PreferenceNotificationRepository preferences,
                         AnnuaireDestinataires annuaire, ModelesNotification modeles,
                         ApplicationEventPublisher evenements) {
        this(notifications, preferences, annuaire, modeles, evenements, Clock.systemUTC());
    }

    Notifications(NotificationRepository notifications, PreferenceNotificationRepository preferences,
                  AnnuaireDestinataires annuaire, ModelesNotification modeles,
                  ApplicationEventPublisher evenements, Clock horloge) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.annuaire = annuaire;
        this.modeles = modeles;
        this.evenements = evenements;
        this.horloge = horloge;
    }

    /**
     * Écrit la notification pour chaque destinataire (nommé ou porteur du rôle
     * demandé). Un destinataire qui a désactivé l'e-mail reçoit la notification
     * dans l'application seulement.
     *
     * @return identifiants des notifications écrites (vide s'il n'y a aucun destinataire)
     */
    @Transactional
    public List<UUID> envoyer(DemandeNotification demande) {
        Set<UUID> destinataires = new LinkedHashSet<>(demande.destinatairesNommes());
        if (demande.roleDestinataire() != null && !demande.roleDestinataire().isBlank()) {
            destinataires.addAll(annuaire.porteursDuRole(demande.roleDestinataire()));
        }
        if (destinataires.isEmpty()) {
            journal.info("Notification {} sans destinataire (objet {} {})", demande.type(), demande.objetType(),
                    demande.objetId());
            return List.of();
        }
        ModelesNotification.Texte texte = modeles.rendre(demande.type(), demande.variables());
        Instant maintenant = horloge.instant();
        List<UUID> ids = new ArrayList<>();
        for (UUID destinataire : destinataires) {
            Notification n = new Notification();
            n.setType(demande.type());
            n.setDestinataireId(destinataire);
            n.setObjetType(demande.objetType());
            n.setObjetId(demande.objetId());
            n.setTitre(texte.titre());
            n.setMessage(texte.message());
            n.setLien(demande.lien());
            n.setCreeLe(maintenant);
            if (courrielActif(destinataire)) {
                n.setCourrielEtat(EtatCourriel.A_ENVOYER);
                n.setCourrielProchainEssai(maintenant);
            } else {
                n.setCourrielEtat(EtatCourriel.DESACTIVE);
            }
            ids.add(notifications.save(n).getId());
        }
        evenements.publishEvent(new NotificationsEcrites(List.copyOf(ids)));
        return ids;
    }

    private boolean courrielActif(UUID utilisateurId) {
        return preferences.findByUtilisateurId(utilisateurId).map(PreferenceNotification::isCourrielActif).orElse(true);
    }
}
