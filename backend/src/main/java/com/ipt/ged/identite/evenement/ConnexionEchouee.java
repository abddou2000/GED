package com.ipt.ged.identite.evenement;

import java.time.Instant;

/**
 * Événement applicatif publié à chaque connexion refusée (dossier technique
 * §3.4.1 : « chaque échec est journalisé dans l'audit »). Ne porte jamais le mot
 * de passe saisi.
 *
 * @param identifiant tel que saisi (borné), éventuellement inconnu de l'annuaire
 */
public record ConnexionEchouee(String identifiant, String adresseIp, MotifEchecConnexion motif,
                               Instant instant) {
}
