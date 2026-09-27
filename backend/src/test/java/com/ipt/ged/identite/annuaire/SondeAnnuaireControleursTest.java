package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Sonde annuaire mesurée par contrôleur de domaine (décision D4). */
class SondeAnnuaireControleursTest {

    /** Annuaire dont seule la sonde compte ; les autres usages sont hors sujet ici. */
    private static Annuaire annuaire(AtomicBoolean joignable, AtomicInteger appels) {
        return new Annuaire() {
            @Override public FicheAnnuaire authentifier(String i, String m) { throw new UnsupportedOperationException(); }
            @Override public Optional<FicheAnnuaire> rechercherParGuid(UUID g) { return Optional.empty(); }
            @Override public Optional<FicheAnnuaire> rechercherParIdentifiant(String i) { return Optional.empty(); }
            @Override public void sonder() {
                appels.incrementAndGet();
                if (!joignable.get()) throw new AnnuaireIndisponibleException(new RuntimeException("muet"));
            }
        };
    }

    @Test
    @DisplayName("Deux contrôleurs : UP si les deux répondent, DEGRADE si un seul, DOWN si aucun ; jauge par contrôleur")
    void plusieursControleurs() {
        AtomicBoolean dc1 = new AtomicBoolean(true);
        AtomicBoolean dc2 = new AtomicBoolean(true);
        Map<String, BooleanSupplier> liaisons = new LinkedHashMap<>();
        liaisons.put("ldaps://dc01:636", dc1::get);
        liaisons.put("ldaps://dc02:636", dc2::get);
        Horloge h = new Horloge();
        SondeAnnuaire sonde = new SondeAnnuaire(annuaire(new AtomicBoolean(true), new AtomicInteger()), liaisons, h);
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        sonde.bindTo(registre);

        assertThat(sonde.health().getStatus()).isEqualTo(Status.UP);

        dc2.set(false);
        h.avancer(31);
        Health degrade = sonde.health();
        assertThat(degrade.getStatus()).isEqualTo(SondeAnnuaire.DEGRADE);
        assertThat(degrade.getDetails().get("controleurs").toString()).contains("dc02:636=DOWN", "dc01:636=UP");
        assertThat(registre.get("ged.annuaire.controleur").tag("controleur", "ldaps://dc01:636").gauge().value()).isEqualTo(1.0);
        assertThat(registre.get("ged.annuaire.controleur").tag("controleur", "ldaps://dc02:636").gauge().value()).isEqualTo(0.0);

        dc1.set(false);
        h.avancer(31);
        assertThat(sonde.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Un seul contrôleur : liaison par la source principale, résultat gardé 30 s")
    void unSeulControleurEtCache() {
        AtomicBoolean joignable = new AtomicBoolean(true);
        AtomicInteger appels = new AtomicInteger();
        Map<String, BooleanSupplier> liaisons = Map.of("ldaps://dc01:636", () -> { throw new AssertionError(); });
        Horloge h = new Horloge();
        SondeAnnuaire sonde = new SondeAnnuaire(annuaire(joignable, appels), liaisons, h);

        assertThat(sonde.health().getStatus()).isEqualTo(Status.UP);
        joignable.set(false);
        assertThat(sonde.health().getStatus()).isEqualTo(Status.UP);   // en cache
        assertThat(appels).hasValue(1);
        h.avancer(31);
        assertThat(sonde.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(sonde.etatControleur("ldaps://dc01:636")).isEqualTo(0.0);
    }

    /** Horloge réglable. */
    private static final class Horloge extends Clock {
        private Instant maintenant = Instant.parse("2026-09-27T10:00:00Z");
        void avancer(long secondes) { maintenant = maintenant.plusSeconds(secondes); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return maintenant; }
    }
}
