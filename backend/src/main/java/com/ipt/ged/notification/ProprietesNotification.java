package com.ipt.ged.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Réglages des notifications (DAT §12.9), sous {@code ged.notification}. Le
 * relais SMTP lui-même se règle sous {@code spring.mail} (notification.yml).
 *
 * @param expediteur       adresse d'expédition des e-mails
 * @param urlApplication   adresse publique du front, pour le lien de l'e-mail
 * @param tentativesMax    nombre total de tentatives d'expédition (3, DAT §12.9)
 * @param delaiReprise     délai avant la 2e tentative, doublé ensuite
 * @param intervalle       période de la relève de la boîte d'envoi
 * @param lot              nombre maximal de notifications expédiées par relève
 * @param expeditionAuto   relève planifiée et expédition dès la validation de la
 *                         transaction ; désactivée en test, où l'expédition est
 *                         déclenchée explicitement
 */
@ConfigurationProperties("ged.notification")
public record ProprietesNotification(String expediteur, String urlApplication, Integer tentativesMax,
                                     Duration delaiReprise, Duration intervalle, Integer lot,
                                     Boolean expeditionAuto) {

    public ProprietesNotification {
        expediteur = expediteur == null || expediteur.isBlank() ? "ged-ne-pas-repondre@localhost" : expediteur.trim();
        urlApplication = urlApplication == null || urlApplication.isBlank() ? null
                : urlApplication.trim().replaceAll("/+$", "");
        tentativesMax = tentativesMax == null ? 3 : Math.max(1, Math.min(tentativesMax, 10));
        delaiReprise = delaiReprise == null ? Duration.ofMinutes(1) : delaiReprise;
        intervalle = intervalle == null ? Duration.ofSeconds(30) : intervalle;
        lot = lot == null ? 50 : Math.max(1, lot);
        expeditionAuto = expeditionAuto == null || expeditionAuto;
    }

    /** Adresse complète d'un chemin de l'application (routage par ancre), ou nul. */
    public String url(String lien) {
        if (urlApplication == null || lien == null || lien.isBlank()) return null;
        return urlApplication + "/#/" + lien.replaceAll("^/+", "");
    }
}
