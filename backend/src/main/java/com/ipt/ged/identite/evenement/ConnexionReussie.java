package com.ipt.ged.identite.evenement;

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
                               boolean premiereConnexion, Instant instant) {
}
