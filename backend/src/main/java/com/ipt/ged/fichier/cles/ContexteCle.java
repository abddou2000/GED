package com.ipt.ged.fichier.cles;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Contexte d'enveloppe d'une DEK : l'identifiant du fichier, sur 16 octets.
 * Centralisé pour que l'écriture, la lecture et la rotation lient la DEK au
 * même contexte — un écart ferait échouer tous les désenveloppements.
 */
public final class ContexteCle {

    private ContexteCle() {}

    public static byte[] de(UUID fichierId) {
        return ByteBuffer.allocate(16)
                .putLong(fichierId.getMostSignificantBits())
                .putLong(fichierId.getLeastSignificantBits())
                .array();
    }
}
