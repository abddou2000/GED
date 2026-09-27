package com.ipt.ged.notification;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.audit.ResultatAudit;

import java.util.Map;
import java.util.UUID;

/**
 * Trace d'audit d'une expédition ou d'un réglage de notification. Ni l'adresse
 * e-mail ni le texte ne sont journalisés : l'identifiant de la notification
 * suffit à les retrouver, et le journal d'audit n'a pas à dupliquer des
 * données personnelles.
 */
record EvenementNotificationAudit(String action, String objetType, UUID objetId, Map<String, Object> apres,
                                  ResultatAudit resultat, String motif, String acteurNom)
        implements EvenementAudit {

    /** Acteur des expéditions : le traitement asynchrone, pas un utilisateur. */
    static final String EXPEDITEUR = "ged:notifications";
}
