package com.ipt.ged.fichier.cles;

/**
 * DEK chiffrée par une KEK : c'est la seule forme sous laquelle une clé de
 * données quitte la mémoire (colonnes {@code dek_enveloppee} et {@code kek_identifiant}
 * de {@code cle_fichier}).
 */
public record CleEnveloppee(String kekId, byte[] octets) {
}
