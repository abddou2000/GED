package com.ipt.ged.identite.evenement;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.audit.ResultatAudit;

import java.time.Instant;

/**
 * Événement applicatif publié à chaque connexion refusée (dossier technique
 * §3.4.1 : « chaque échec est journalisé dans l'audit »). Ne porte jamais le mot
 * de passe saisi.
 *
 * @param identifiant tel que saisi (borné), éventuellement inconnu de l'annuaire
 */
public record ConnexionEchouee(String identifiant, String adresseIp, MotifEchecConnexion motifEchec,
                               Instant instant) implements EvenementAudit {

    @Override
    public String action() { return "CONNEXION_REFUSEE"; }

    @Override
    public String objetType() { return "UTILISATEUR"; }

    @Override
    public ResultatAudit resultat() { return ResultatAudit.REFUS; }

    /** Motif à code stable ; jamais le mot de passe saisi. */
    @Override
    public String motif() { return motifEchec == null ? null : motifEchec.name(); }

    /** Identifiant tel que saisi : la personne peut ne pas exister. */
    @Override
    public String acteurNom() { return identifiant; }
}
