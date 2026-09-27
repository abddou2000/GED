package com.ipt.ged.identite.annuaire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Conversion de l'attribut binaire {@code objectGUID} d'Active Directory.
 *
 * <p>AD stocke le GUID sur 16 octets dont les trois premiers champs sont en
 * petit-boutiste (structure GUID de Microsoft), alors que {@link UUID} lit tout
 * en gros-boutiste. Sans cette permutation, la clé enregistrée ne ressemblerait
 * pas au GUID qu'affichent les outils d'administration AD, et une recherche par
 * GUID ne retrouverait jamais l'entrée.
 */
public final class GuidAnnuaire {

    private GuidAnnuaire() {}

    /** Octets tels que l'annuaire les renvoie → UUID sous sa forme usuelle. */
    public static UUID depuisOctets(byte[] octets) {
        if (octets == null || octets.length != 16) {
            throw new IllegalArgumentException("objectGUID invalide : 16 octets attendus");
        }
        ByteBuffer source = ByteBuffer.wrap(octets);
        ByteBuffer cible = ByteBuffer.allocate(16);
        cible.putInt(source.order(ByteOrder.LITTLE_ENDIAN).getInt());
        cible.putShort(source.getShort());
        cible.putShort(source.getShort());
        source.order(ByteOrder.BIG_ENDIAN);
        cible.putLong(source.getLong());
        cible.flip();
        return new UUID(cible.getLong(), cible.getLong());
    }

    /** Inverse de {@link #depuisOctets} : octets dans l'ordre de l'annuaire. */
    public static byte[] versOctets(UUID guid) {
        ByteBuffer source = ByteBuffer.allocate(16)
                .putLong(guid.getMostSignificantBits())
                .putLong(guid.getLeastSignificantBits());
        source.flip();
        ByteBuffer cible = ByteBuffer.allocate(16);
        cible.order(ByteOrder.LITTLE_ENDIAN).putInt(source.getInt());
        cible.putShort(source.getShort());
        cible.putShort(source.getShort());
        cible.order(ByteOrder.BIG_ENDIAN).putLong(source.getLong());
        return cible.array();
    }

    /** Valeur de filtre LDAP échappée octet par octet ({@code \ab\cd…}), sûre par construction. */
    public static String pourFiltre(UUID guid) {
        StringBuilder sb = new StringBuilder(48);
        for (byte b : versOctets(guid)) {
            sb.append('\\').append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
