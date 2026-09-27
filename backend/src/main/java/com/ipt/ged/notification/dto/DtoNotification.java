package com.ipt.ged.notification.dto;

import com.ipt.ged.notification.EtatCourriel;
import com.ipt.ged.notification.Notification;
import com.ipt.ged.notification.TypeNotification;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Charges utiles du centre de notifications ({@code /api/v1/notifications}). */
public final class DtoNotification {

    private DtoNotification() {}

    /**
     * Notification telle que l'utilisateur la voit.
     *
     * @param famille  CIRCUIT_VALIDATION, ACCES_ESPACE ou FIN_CONSERVATION
     * @param lien     chemin relatif dans l'application, ou nul
     * @param courriel état de l'expédition par e-mail
     */
    public record NotificationResponse(UUID id, TypeNotification type, TypeNotification.Famille famille,
                                       String titre, String message, String lien, String objetType, UUID objetId,
                                       Instant creeLe, Instant lueLe, boolean lue, EtatCourriel courriel) {

        public static NotificationResponse de(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getType().famille(), n.getTitre(),
                    n.getMessage(), n.getLien(), n.getObjetType(), n.getObjetId(), n.getCreeLe(), n.getLueLe(),
                    n.getLueLe() != null, n.getCourrielEtat());
        }
    }

    /** Pastille : nombre de notifications non lues. */
    public record CompteurResponse(long nonLues) {}

    /** Résultat d'un marquage global. */
    public record MarquageResponse(int marquees) {}

    /** Préférence : l'e-mail peut être désactivé, la notification in-app subsiste. */
    public record PreferenceRequest(@NotNull Boolean courrielActif) {}

    public record PreferenceResponse(boolean courrielActif, boolean inAppActif) {}
}
