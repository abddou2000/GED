package com.ipt.ged.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les clés UUID v7 doivent rester triables dans l'ordre d'émission : les tris
 * « le plus récent d'abord » et l'ordre des versions en dépendent.
 */
class UuidV7Test {

    @Test
    @DisplayName("Version 7, variante RFC 9562")
    void formatRfc9562() {
        UUID id = UuidV7.suivant();
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    @DisplayName("Ordre strictement croissant, y compris dans la même milliseconde")
    void monotone() {
        // La forme texte en minuscules suit l'ordre des octets, celui qu'applique
        // PostgreSQL à une colonne uuid : c'est lui qui compte pour ORDER BY id.
        String precedent = UuidV7.suivant().toString();
        Set<String> vus = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String courant = UuidV7.suivant().toString();
            assertTrue(courant.compareTo(precedent) > 0, precedent + " puis " + courant);
            assertTrue(vus.add(courant), "doublon : " + courant);
            precedent = courant;
        }
    }

    @Test
    @DisplayName("L'horodatage occupe les 48 bits de tête")
    void horodatage() {
        long avant = System.currentTimeMillis();
        UUID id = UuidV7.suivant();
        long instant = id.getMostSignificantBits() >>> 16;
        assertTrue(instant >= avant && instant <= System.currentTimeMillis() + 1000);
        assertEquals(UuidV7.construire(instant, 0, 0L).getMostSignificantBits() >>> 16, instant);
    }
}
