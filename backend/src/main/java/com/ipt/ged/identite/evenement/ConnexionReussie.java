package com.ipt.ged.identite.evenement;

import com.ipt.ged.audit.EvenementAudit;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement applicatif publié à chaque connexion réussie (dossier technique
 * §3.4.1, §7.4.1). Le journal d'audit (lot E4) l'écoute ; le module d'identité
 * n'a aucune dépendance vers lui.
 *
 * @param premiereConnexion vrai si l'identité vient d'être provisionnée
 */
public record ConnexionReussie(UUID utilisateurId, String identifiant, String adresseIp,
                               boolean premiereConnexion, Instant instant) implements EvenementAudit {

    @Override
    public String action() { return "CONNEXION_REUSSIE"; }

    @Override
    public String objetType() { return "UTILISATEUR"; }

    @Override
    public UUID objetId() { return utilisateurId; }

    @Override
    public java.util.Map<String, Object> apres() {
        return java.util.Map.of("premiereConnexion", premiereConnexion);
    }

    /** La requête de connexion n'est pas encore authentifiée : l'acteur est porté ici. */
    @Override
    public UUID acteurUtilisateurId() { return utilisateurId; }

    @Override
    public String acteurNom() { return identifiant; }
}
