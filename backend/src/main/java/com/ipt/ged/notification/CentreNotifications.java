package com.ipt.ged.notification;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.ResultatAudit;
import com.ipt.ged.common.PageResponse;
import com.ipt.ged.common.Tri;
import com.ipt.ged.common.erreur.NonAuthentifieException;
import com.ipt.ged.common.erreur.RessourceIntrouvableException;
import com.ipt.ged.notification.dto.DtoNotification.CompteurResponse;
import com.ipt.ged.notification.dto.DtoNotification.MarquageResponse;
import com.ipt.ged.notification.dto.DtoNotification.NotificationResponse;
import com.ipt.ged.notification.dto.DtoNotification.PreferenceResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Centre de notifications de l'utilisateur (DAT §12.9) : liste, pastille,
 * marquage lu, préférence e-mail. Chacun ne voit et ne modifie que ses propres
 * notifications ; celle d'un autre est « introuvable » (404, principe P5).
 */
@Service
public class CentreNotifications {

    private static final Set<String> TRI = Set.of("creeLe");

    private final NotificationRepository notifications;
    private final PreferenceNotificationRepository preferences;
    private final IdentiteDestinataire identite;
    private final AuditService audit;
    private final Clock horloge = Clock.systemUTC();

    public CentreNotifications(NotificationRepository notifications, PreferenceNotificationRepository preferences,
                               IdentiteDestinataire identite, AuditService audit) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.identite = identite;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> lister(boolean nonLuesSeulement, int page, int size) {
        UUID moi = moi();
        Pageable p = Tri.pageable(page, size, "creeLe", "desc", TRI, TRI);
        return PageResponse.of(nonLuesSeulement
                ? notifications.findByDestinataireIdAndLueLeIsNull(moi, p)
                : notifications.findByDestinataireId(moi, p), NotificationResponse::de);
    }

    @Transactional(readOnly = true)
    public CompteurResponse compter() {
        return new CompteurResponse(notifications.countByDestinataireIdAndLueLeIsNull(moi()));
    }

    /** Marque lue une notification du demandeur ; sans effet si elle l'était déjà. */
    @Transactional
    public NotificationResponse marquerLue(UUID id) {
        Notification n = notifications.findByIdAndDestinataireId(id, moi())
                .orElseThrow(RessourceIntrouvableException::new);
        if (n.getLueLe() == null) n.setLueLe(horloge.instant());
        return NotificationResponse.de(n);
    }

    @Transactional
    public MarquageResponse marquerToutesLues() {
        return new MarquageResponse(notifications.marquerToutesLues(moi(), horloge.instant()));
    }

    @Transactional(readOnly = true)
    public PreferenceResponse preference() {
        return new PreferenceResponse(preferences.findByUtilisateurId(moi())
                .map(PreferenceNotification::isCourrielActif).orElse(true), true);
    }

    /** Active ou désactive l'e-mail ; la modification est auditée. */
    @Transactional
    public PreferenceResponse definirPreference(boolean courrielActif) {
        UUID moi = moi();
        PreferenceNotification p = preferences.findByUtilisateurId(moi).orElseGet(() -> new PreferenceNotification(moi));
        boolean avant = p.isCourrielActif();
        p.setCourrielActif(courrielActif);
        p.setModifieLe(horloge.instant());
        preferences.save(p);
        if (avant != courrielActif) {
            Map<String, Object> apres = new LinkedHashMap<>();
            apres.put("courrielActif", courrielActif);
            audit.enregistrer(new EvenementNotificationAudit(ActionAudit.PREFERENCE_NOTIFICATION_MODIFIEE.code(),
                    "PREFERENCE_NOTIFICATION", moi, apres, ResultatAudit.SUCCES, null, null));
        }
        return new PreferenceResponse(courrielActif, true);
    }

    private UUID moi() {
        UUID moi = identite.courante();
        if (moi == null) throw new NonAuthentifieException("Notifications réservées à un utilisateur authentifié.");
        return moi;
    }
}
