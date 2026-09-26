package com.ipt.ged.fichier.chiffrement;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Format des fichiers chiffrés {@code .enc} (version 1).
 *
 * <pre>
 *  en-tête (21 octets) : "GEDC" | version (1) | taille de segment (4) | IV de base (12)
 *  segments            : chiffré AES-256-GCM (≤ taille de segment) | étiquette (16)
 * </pre>
 *
 * <p><b>Pourquoi des segments.</b> AES-GCM n'authentifie un message qu'à sa
 * fin : déchiffrer un fichier de 200 Mo d'un seul tenant oblige soit à tout
 * garder en mémoire avant de rendre le premier octet (c'est ce que fait le JDK),
 * soit à livrer du clair non vérifié. Chaque segment est donc un message GCM
 * complet, vérifié avant d'être rendu : la mémoire reste bornée à un segment et
 * aucun octet altéré n'atteint jamais le lecteur.
 *
 * <p><b>Nonces.</b> L'IV de base (96 bits) est tiré au hasard pour chaque
 * fichier ; le nonce du segment est dérivé de cet IV, du rang du segment et
 * d'un indicateur « dernier segment » (construction STREAM de Hoang, Reyhanitabar,
 * Rogaway et Vizár, celle du « streaming AEAD » de Tink). La DEK étant propre
 * au fichier, les nonces n'ont à être uniques qu'à l'intérieur du fichier, ce
 * que le rang garantit.
 *
 * <p><b>Ordre et troncature.</b> Le rang et l'indicateur « dernier » sont liés
 * au nonce, et répétés avec l'en-tête et l'identifiant du fichier dans les
 * données authentifiées : permuter, supprimer, tronquer ou prolonger le
 * fichier, ou le recopier sous un autre identifiant, fait échouer
 * l'authentification.
 */
public final class FormatChiffre {

    private FormatChiffre() {}

    static final byte[] MAGIQUE = {'G', 'E', 'D', 'C'};
    static final byte VERSION = 1;
    public static final int IV_OCTETS = 12;
    public static final int ETIQUETTE_OCTETS = 16;
    static final int ETIQUETTE_BITS = ETIQUETTE_OCTETS * 8;
    public static final int ENTETE_OCTETS = MAGIQUE.length + 1 + 4 + IV_OCTETS;
    /** 1 Mio : mémoire bornée à deux segments par flux, surcoût de 16 octets par Mio. */
    public static final int SEGMENT_PAR_DEFAUT = 1024 * 1024;
    /** Bornes lues dans l'en-tête : un en-tête forgé ne doit pas faire allouer 2 Go. */
    static final int SEGMENT_MIN = 1024;
    static final int SEGMENT_MAX = 8 * 1024 * 1024;
    static final String TRANSFORMATION = "AES/GCM/NoPadding";

    static byte[] entete(int tailleSegment, byte[] iv) {
        return ByteBuffer.allocate(ENTETE_OCTETS)
                .put(MAGIQUE).put(VERSION).putInt(tailleSegment).put(iv)
                .array();
    }

    /**
     * Nonce du segment : IV de base combiné par OU exclusif avec le rang du
     * segment (octets 4 à 10) et l'indicateur « dernier » (octet 11). Chaque
     * couple (rang, dernier) donne un masque distinct, donc un nonce distinct.
     */
    static byte[] nonce(byte[] ivBase, long rang, boolean dernier) {
        if (rang < 0 || rang >= (1L << 56)) {
            throw new IllegalStateException("Nombre de segments hors limite");
        }
        byte[] n = ivBase.clone();
        n[IV_OCTETS - 1] ^= (byte) (dernier ? 1 : 0);
        for (int i = 0; i < 7; i++) {
            n[IV_OCTETS - 2 - i] ^= (byte) (rang >>> (8 * i));
        }
        return n;
    }

    static byte[] aad(byte[] entete, UUID id, long rang, boolean dernier) {
        return ByteBuffer.allocate(entete.length + 16 + 8 + 1)
                .put(entete)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putLong(rang)
                .put((byte) (dernier ? 1 : 0))
                .array();
    }

    /**
     * Taille en clair déduite de la taille chiffrée, sans rien déchiffrer
     * (en-tête {@code Content-Length} de la prévisualisation).
     */
    public static long tailleEnClair(long tailleChiffree, int tailleSegment) {
        long corps = tailleChiffree - ENTETE_OCTETS;
        if (corps < ETIQUETTE_OCTETS) return -1;
        long segmentChiffre = (long) tailleSegment + ETIQUETTE_OCTETS;
        long segments = (corps + segmentChiffre - 1) / segmentChiffre;
        return corps - segments * ETIQUETTE_OCTETS;
    }
}
