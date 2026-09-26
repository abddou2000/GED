package com.ipt.ged.fichier.cles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** §6.1.2 — KEK en keystore PKCS#12 derrière KeyProvider. */
class KeystoreKeyProviderTest {

    private static final char[] MDP = "phrase-de-test".toCharArray();

    @TempDir Path dossier;

    private KeystoreKeyProvider nouveau() {
        return new KeystoreKeyProvider(dossier.resolve("kek.p12"), MDP, true);
    }

    @Test
    @DisplayName("Keystore créé au format PKCS#12, avec une première KEK active")
    void creation() throws Exception {
        KeystoreKeyProvider p = nouveau();
        assertEquals("kek-00001", p.kekActive());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dossier.resolve("kek.p12"))) {
            ks.load(in, MDP);
        }
        assertTrue(ks.isKeyEntry("kek-00001"));
    }

    @Test
    @DisplayName("Enveloppe puis désenveloppe une DEK ; l'enveloppe ne contient pas la DEK")
    void enveloppe() {
        KeystoreKeyProvider p = nouveau();
        byte[] dek = new byte[32];
        new java.security.SecureRandom().nextBytes(dek);
        byte[] ctx = ContexteCle.de(UUID.randomUUID());
        CleEnveloppee env = p.envelopper(dek, ctx);
        assertEquals("kek-00001", env.kekId());
        assertEquals(12 + 32 + 16, env.octets().length);
        assertFalse(java.util.Collections.indexOfSubList(
                java.util.Arrays.asList(box(env.octets())), java.util.Arrays.asList(box(dek))) >= 0);
        assertArrayEquals(dek, p.desenvelopper(env, ctx));
    }

    @Test
    @DisplayName("Une DEK enveloppée pour un fichier ne se désenveloppe pas pour un autre")
    void contexteLie() {
        KeystoreKeyProvider p = nouveau();
        CleEnveloppee env = p.envelopper(new byte[32], ContexteCle.de(UUID.randomUUID()));
        assertThrows(CleIndisponibleException.class, () -> p.desenvelopper(env, ContexteCle.de(UUID.randomUUID())));
        byte[] altere = env.octets().clone();
        altere[20] ^= 1;
        assertThrows(CleIndisponibleException.class,
                () -> p.desenvelopper(new CleEnveloppee(env.kekId(), altere), ContexteCle.de(UUID.randomUUID())));
    }

    @Test
    @DisplayName("Rechargé depuis le disque, le keystore rend les mêmes KEK")
    void rechargement() {
        byte[] ctx = ContexteCle.de(UUID.randomUUID());
        CleEnveloppee env = nouveau().envelopper(new byte[]{1, 2, 3}, ctx);
        KeystoreKeyProvider relu = new KeystoreKeyProvider(dossier.resolve("kek.p12"), MDP, false);
        assertArrayEquals(new byte[]{1, 2, 3}, relu.desenvelopper(env, ctx));
    }

    @Test
    @DisplayName("Mauvaise phrase secrète, phrase absente ou keystore absent en production : refus de démarrer")
    void refusDeDemarrer() {
        nouveau();
        assertThrows(IllegalStateException.class,
                () -> new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mauvaise".toCharArray(), false));
        assertThrows(IllegalStateException.class,
                () -> new KeystoreKeyProvider(dossier.resolve("kek.p12"), new char[0], false));
        assertThrows(IllegalStateException.class,
                () -> new KeystoreKeyProvider(dossier.resolve("absent.p12"), MDP, false));
        assertFalse(Files.exists(dossier.resolve("absent.p12")), "aucun keystore neuf ne doit être créé");
    }

    @Test
    @DisplayName("Nouvelle KEK : elle devient active, l'ancienne reste utilisable désactivée")
    void nouvelleKek() {
        KeystoreKeyProvider p = nouveau();
        byte[] ctx = ContexteCle.de(UUID.randomUUID());
        CleEnveloppee ancienne = p.envelopper(new byte[]{9}, ctx);
        assertEquals("kek-00002", p.nouvelleKek());
        assertEquals("kek-00002", p.kekActive());
        assertEquals(Set.of("kek-00001", "kek-00002"), p.kekConnues());
        assertEquals("kek-00002", p.envelopper(new byte[]{9}, ctx).kekId());
        assertArrayEquals(new byte[]{9}, p.desenvelopper(ancienne, ctx));
        // Persistée : un redémarrage retrouve les deux KEK et la même active.
        KeystoreKeyProvider relu = new KeystoreKeyProvider(dossier.resolve("kek.p12"), MDP, false);
        assertEquals("kek-00002", relu.kekActive());
        assertArrayEquals(new byte[]{9}, relu.desenvelopper(ancienne, ctx));
    }

    @Test
    @DisplayName("Retrait : la KEK active est protégée, une ancienne peut être retirée")
    void retrait() {
        KeystoreKeyProvider p = nouveau();
        p.nouvelleKek();
        assertThrows(IllegalStateException.class, () -> p.retirer("kek-00002"));
        CleEnveloppee sousAncienne = new CleEnveloppee("kek-00001", new byte[60]);
        p.retirer("kek-00001");
        assertEquals(Set.of("kek-00002"), p.kekConnues());
        assertThrows(CleIndisponibleException.class, () -> p.desenvelopper(sousAncienne, new byte[16]));
    }

    private static Byte[] box(byte[] b) {
        Byte[] r = new Byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[i];
        return r;
    }
}
