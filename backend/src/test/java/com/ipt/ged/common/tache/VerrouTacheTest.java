package com.ipt.ged.common.tache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verrou des tâches planifiées (§12.9) : trois « instances » (trois horloges)
 * se disputent la même tâche sur la base réelle.
 */
@SpringBootTest
@ActiveProfiles("test")
class VerrouTacheTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private VerrouTache instance(Instant a) {
        return new VerrouTache(jdbc, transactions, Clock.fixed(a, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Un seul détenteur à la fois ; libéré, le verrou se reprend ; un bail expiré est repris (instance tombée)")
    void unSeulDetenteur() {
        String tache = "TEST_" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-10-03T06:00:00Z");

        Optional<VerrouTache.Jeton> a = instance(t0).prendre(tache, Duration.ofHours(1));
        assertThat(a).isPresent();
        assertThat(instance(t0.plusSeconds(1)).prendre(tache, Duration.ofHours(1))).as("bail en cours").isEmpty();
        assertThat(instance(t0.plusSeconds(1800)).prendre(tache, Duration.ofHours(1))).isEmpty();

        instance(t0.plusSeconds(60)).liberer(a.get());
        Optional<VerrouTache.Jeton> b = instance(t0.plusSeconds(61)).prendre(tache, Duration.ofHours(1));
        assertThat(b).as("libéré : repris").isPresent();
        assertThat(jdbc.queryForObject("SELECT derniere_fin IS NOT NULL FROM verrou_tache WHERE nom = ?",
                Boolean.class, tache)).isTrue();

        // L'instance B tombe sans libérer : son bail expire, C reprend la tâche.
        Optional<VerrouTache.Jeton> c = instance(t0.plusSeconds(61 + 3601)).prendre(tache, Duration.ofHours(1));
        assertThat(c).isPresent();
        // B, revenu, ne libère pas le verrou de C.
        instance(t0.plusSeconds(61 + 3700)).liberer(b.get());
        assertThat(instance(t0.plusSeconds(61 + 3701)).prendre(tache, Duration.ofHours(1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT detenteur FROM verrou_tache WHERE nom = ?", String.class, tache))
                .isEqualTo(c.get().detenteur());

        assertThatThrownBy(() -> instance(t0).prendre(tache, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
