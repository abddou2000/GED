package com.ipt.ged.identite.evenement;

import com.ipt.ged.audit.EvenementAudit;

import com.ipt.ged.identite.session.MotifRevocation;

import java.time.Instant;
import java.util.UUID;

/**
 * Sessions d'un utilisateur révoquées : déconnexion, révocation par
 * l'Administrateur ou réutilisation d'un jeton consommé (vol présumé).
 *
 * @param parUtilisateurId auteur de la révocation ; {@code null} pour une révocation automatique
 * @param nombre           lignes de session révoquées
 */
public record SessionsRevoquees(UUID utilisateurId, UUID parUtilisateurId, MotifRevocation motifRevocation,
                                int nombre, Instant instant) implements EvenementAudit {

    /**
     * Une déconnexion est tracée comme telle ({@code DECONNEXION}, catalogue
     * §3.4.1, ANO-E4-003) ; les autres révocations gardent leur code.
     */
    @Override
    public String action() {
        return motifRevocation == MotifRevocation.DECONNEXION ? "DECONNEXION" : "SESSIONS_REVOQUEES";
    }

    @Override
    public String objetType() { return "UTILISATEUR"; }

    @Override
    public UUID objetId() { return utilisateurId; }

    @Override
    public java.util.Map<String, Object> apres() {
        return java.util.Map.of("sessionsRevoquees", nombre);
    }

    @Override
    public String motif() { return motifRevocation == null ? null : motifRevocation.name(); }

    /** Auteur de la révocation ; {@code null} (automatique) = celui de la requête. */
    @Override
    public UUID acteurUtilisateurId() { return parUtilisateurId; }
}
