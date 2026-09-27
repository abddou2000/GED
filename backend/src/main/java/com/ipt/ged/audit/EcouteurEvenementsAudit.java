package com.ipt.ged.audit;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inscrit au journal tout {@link EvenementAudit} publié par un lot
 * ({@code ApplicationEventPublisher#publishEvent}).
 *
 * <p>Écouteur synchrone : il s'exécute dans le fil et la transaction de
 * l'éditeur. Un succès est donc écrit avec l'action (tout ou rien), un refus
 * dans une transaction propre (voir {@link AuditService#enregistrer}).
 */
@Component
public class EcouteurEvenementsAudit {

    private final AuditService audit;

    public EcouteurEvenementsAudit(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void surEvenement(EvenementAudit evenement) {
        audit.enregistrer(evenement);
    }
}
