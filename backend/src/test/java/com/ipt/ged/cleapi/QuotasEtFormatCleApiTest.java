package com.ipt.ged.cleapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Format des clés et fenêtres de quotas, sans base (horloge contrôlée). */
class QuotasEtFormatCleApiTest {

    /** Horloge réglable à la main. */
    private static final class Horloge extends Clock {
        Instant maintenant;

        Horloge(Instant debut) {
            this.maintenant = debut;
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return maintenant; }
    }

    private static CleApi cle() {
        CleApi c = new CleApi();
        c.setId(UUID.randomUUID());
        return c;
    }

    @Test
    @DisplayName("Format : génération puis lecture, secret de 256 bits, empreinte comparée à temps constant")
    void format() {
        FormatCleApi.CleGeneree g = FormatCleApi.generer("prod");
        FormatCleApi.CleLue lue = FormatCleApi.lire(g.valeur()).orElseThrow();
        assertThat(lue.environnement()).isEqualTo("prod");
        assertThat(lue.identifiant()).isEqualTo(g.identifiant());
        assertThat(lue.secret()).hasSize(64);
        assertThat(FormatCleApi.memeEmpreinte(FormatCleApi.empreinte(lue.secret()), g.empreinteSecret())).isTrue();
        assertThat(FormatCleApi.lire("ged_prod_" + g.identifiant() + "_court")).isEmpty();
        assertThat(FormatCleApi.lire("Bearer abc")).isEmpty();
        assertThat(FormatCleApi.generer("prod").valeur()).isNotEqualTo(g.valeur());
    }

    @Test
    @DisplayName("Quota par minute : refus au-delà, avec délai jusqu'à la minute suivante ; nouvelle minute, nouveau crédit")
    void quotaMinute() {
        Horloge h = new Horloge(Instant.parse("2026-09-28T10:15:40Z"));
        QuotasCleApi q = new QuotasCleApi(null, h);
        CleApi c = cle();
        assertThat(q.consommer(c, 2, 100).accepte()).isTrue();
        assertThat(q.consommer(c, 2, 100).accepte()).isTrue();
        QuotasCleApi.Decision refus = q.consommer(c, 2, 100);
        assertThat(refus.accepte()).isFalse();
        assertThat(refus.code()).isEqualTo(CodesErreurCleApi.QUOTA_MINUTE_DEPASSE);
        assertThat(refus.reessayerApres().toSeconds()).isEqualTo(20);
        h.maintenant = Instant.parse("2026-09-28T10:16:00Z");
        assertThat(q.consommer(c, 2, 100).accepte()).isTrue();
    }

    @Test
    @DisplayName("Quota journalier : refus jusqu'à minuit UTC, compteur du jour repris de la base")
    void quotaJour() {
        Horloge h = new Horloge(Instant.parse("2026-09-28T23:59:30Z"));
        QuotasCleApi q = new QuotasCleApi(null, h);
        CleApi c = cle();
        c.setQuotaJourDate(LocalDate.parse("2026-09-28"));
        c.setQuotaJourAppels(5);    // persisté avant un redémarrage
        QuotasCleApi.Decision refus = q.consommer(c, 600, 5);
        assertThat(refus.accepte()).isFalse();
        assertThat(refus.code()).isEqualTo(CodesErreurCleApi.QUOTA_JOUR_DEPASSE);
        assertThat(refus.reessayerApres().toSeconds()).isEqualTo(30);
        h.maintenant = Instant.parse("2026-09-29T00:00:01Z");
        assertThat(q.consommer(c, 600, 5).accepte()).isTrue();
    }

    @Test
    @DisplayName("Adresses autorisées : exactes et plages CIDR ; liste vide = toute adresse ; entrée illisible = rien")
    void adresses() {
        assertThat(AuthentificationCleApi.adresseAutorisee(List.of(), "1.2.3.4")).isTrue();
        assertThat(AuthentificationCleApi.adresseAutorisee(List.of("10.0.0.0/8"), "10.20.30.40")).isTrue();
        assertThat(AuthentificationCleApi.adresseAutorisee(List.of("10.0.0.5"), "10.0.0.6")).isFalse();
        assertThat(AuthentificationCleApi.adresseAutorisee(List.of("pas une adresse"), "10.0.0.6")).isFalse();
        assertThat(AuthentificationCleApi.adresseAutorisee(List.of("10.0.0.5"), null)).isFalse();
    }
}
