package com.ipt.ged.notification;

import com.ipt.ged.common.PageResponse;
import com.ipt.ged.notification.dto.DtoNotification.CompteurResponse;
import com.ipt.ged.notification.dto.DtoNotification.MarquageResponse;
import com.ipt.ged.notification.dto.DtoNotification.NotificationResponse;
import com.ipt.ged.notification.dto.DtoNotification.PreferenceRequest;
import com.ipt.ged.notification.dto.DtoNotification.PreferenceResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Centre de notifications de l'utilisateur connecté (DAT §12.9) : pastille,
 * liste, marquage lu, préférence e-mail. Fermé aux applications (clés d'API) :
 * voir {@code ConfigurationSecuriteApplications}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {

    private final CentreNotifications centre;

    public NotificationsController(CentreNotifications centre) {
        this.centre = centre;
    }

    /** Mes notifications, les plus récentes d'abord ; {@code nonLues=true} pour la seule file à traiter. */
    @GetMapping
    public PageResponse<NotificationResponse> lister(@RequestParam(defaultValue = "false") boolean nonLues,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return centre.lister(nonLues, page, size);
    }

    /** Pastille de la barre supérieure. */
    @GetMapping("/compteur")
    public CompteurResponse compteur() {
        return centre.compter();
    }

    @PostMapping("/{id}/lecture")
    public NotificationResponse marquerLue(@PathVariable UUID id) {
        return centre.marquerLue(id);
    }

    @PostMapping("/lecture")
    public MarquageResponse marquerToutesLues() {
        return centre.marquerToutesLues();
    }

    @GetMapping("/preferences")
    public PreferenceResponse preference() {
        return centre.preference();
    }

    @PutMapping("/preferences")
    public PreferenceResponse definirPreference(@Valid @RequestBody PreferenceRequest requete) {
        return centre.definirPreference(requete.courrielActif());
    }
}
