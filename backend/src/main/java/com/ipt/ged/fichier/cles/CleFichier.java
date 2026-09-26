package com.ipt.ged.fichier.cles;

import java.util.UUID;

/**
 * Ligne de la table {@code cle_fichier} (§6.1.2) : la DEK d'un fichier, sous
 * enveloppe.
 *
 * @param id            identifiant du fichier stocké (même UUID que le nom
 *                      du fichier {@code aa/bb/<uuid>.enc}) ;
 * @param dekEnveloppee DEK chiffrée par la KEK ({@link CleEnveloppee#octets()}) ;
 * @param kekId         KEK ayant servi à l'enveloppe ;
 * @param algorithme    algorithme de chiffrement du fichier.
 */
public record CleFichier(UUID id, byte[] dekEnveloppee, String kekId, String algorithme) {

    /** Seul algorithme admis par le dossier technique. */
    public static final String AES_256_GCM = "AES-256-GCM";

    public CleEnveloppee enveloppe() {
        return new CleEnveloppee(kekId, dekEnveloppee);
    }
}
