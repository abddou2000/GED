package com.ipt.ged.document.conservation;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.notification.DemandeNotification;
import com.ipt.ged.notification.EvenementNotifiable;
import com.ipt.ged.notification.TypeNotification;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Échéance de conservation d'un document atteinte et signalée (DAT §12.9,
 * T-112) : une trace d'audit ({@code ECHEANCE_CONSERVATION_ATTEINTE}) et une
 * notification de la famille « fin de conservation » aux Agents d'archive
 * compétents pour le document, dans la transaction du marquage.
 *
 * <p>Aucune suppression : le signalement invite l'Agent d'archive à examiner
 * le document (P4).
 *
 * @param destinataires Agents d'archive qui peuvent archiver ce document
 *                      (rôle et confidentialité appliqués) ; vide = trace seule
 */
public record EcheanceConservationAtteinte(UUID documentId, String document, LocalDate echeance,
                                           List<UUID> destinataires) implements EvenementAudit, EvenementNotifiable {

    public static final String ACTION = "ECHEANCE_CONSERVATION_ATTEINTE";

    public EcheanceConservationAtteinte {
        destinataires = destinataires == null ? List.of() : List.copyOf(destinataires);
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public String objetType() {
        return "DOCUMENT";
    }

    @Override
    public UUID objetId() {
        return documentId;
    }

    @Override
    public Map<String, Object> apres() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("echeance", echeance.toString());
        m.put("agentsNotifies", destinataires.size());
        return m;
    }

    /** Tâche planifiée : pas d'utilisateur authentifié à qui imputer l'action. */
    @Override
    public String acteurNom() {
        return "Tâche planifiée";
    }

    @Override
    public DemandeNotification notification() {
        if (destinataires.isEmpty()) return null;
        return DemandeNotification.a(TypeNotification.ECHEANCE_CONSERVATION, destinataires, "DOCUMENT", documentId,
                Map.of("document", document, "echeance", echeance.toString()), "documents/" + documentId);
    }
}
