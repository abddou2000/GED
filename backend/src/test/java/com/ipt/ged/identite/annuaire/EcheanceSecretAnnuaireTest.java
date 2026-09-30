package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * ANO-E10-002 (second point), P-02 : échéance du secret du compte de service
 * publiée en jours à /actuator/prometheus. Vérifié contre le simulateur
 * d'Active Directory : les attributs d'AD y sont posés à la main (le
 * simulateur ne calcule pas {@code msDS-UserPasswordExpiryTimeComputed}).
 */
class EcheanceSecretAnnuaireTest {

    private static final String BASE = "DC=marchicamed,DC=ma";
    private static final String SERVICE = "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma";

    /** Horloge réglable. */
    private static final class Horloge extends Clock {
        private Instant maintenant = Instant.parse("2026-09-30T08:00:00Z");

        void avancer(Duration d) {
            maintenant = maintenant.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return maintenant; }
    }

    private final Horloge horloge = new Horloge();
    private SimulateurAnnuaire simulateur;

    @BeforeEach
    void demarrer() throws Exception {
        try (InputStream ldif = getClass().getResourceAsStream("/annuaire/annuaire-test.ldif")) {
            simulateur = SimulateurAnnuaire.demarrer(BASE, 0, ldif);
        }
    }

    @AfterEach
    void arreter() {
        simulateur.arreter();
    }

    private EcheanceSecretAnnuaire echeance(LocalDate declaree) {
        ProprietesIdentite p = new ProprietesIdentite();
        p.getAnnuaire().setUrls(List.of(simulateur.url()));
        p.getAnnuaire().setBase(BASE);
        p.getAnnuaire().setCompteService(SERVICE);
        p.getAnnuaire().setMotDePasseService("test-only-password");
        p.getAnnuaire().setExigerLdaps(false);
        p.getAnnuaire().setPool(false);
        p.getAnnuaire().setDelaiConnexion(Duration.ofSeconds(1));
        p.getAnnuaire().setDelaiLecture(Duration.ofSeconds(2));
        return new EcheanceSecretAnnuaire(new ConfigurationAnnuaire().controleursAnnuaire(p), SERVICE, declaree, horloge);
    }

    /** Date FILETIME d'AD : centaines de nanosecondes depuis le 1er janvier 1601. */
    private static String filetime(Instant i) {
        return String.valueOf((i.getEpochSecond() + 11_644_473_600L) * 10_000_000L + i.getNano() / 100);
    }

    private void poser(String attribut, String valeur) throws Exception {
        simulateur.serveur().modify(SERVICE, new Modification(ModificationType.REPLACE, attribut, valeur));
    }

    @Test
    @DisplayName("Jours restants lus sur le compte de service : la plus proche de l'échéance du mot de passe et de la fin du compte")
    void joursRestants() throws Exception {
        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, filetime(horloge.instant().plus(Duration.ofDays(10))));
        poser(EcheanceSecretAnnuaire.EXPIRATION_COMPTE, "0");   // compte sans fin
        EcheanceSecretAnnuaire e = echeance(null);
        assertThat(e.joursRestants()).isCloseTo(10.0, within(0.001));

        // Gardée une heure : une modification dans l'annuaire n'est vue qu'à la relecture.
        poser(EcheanceSecretAnnuaire.EXPIRATION_COMPTE, filetime(horloge.instant().plus(Duration.ofDays(4))));
        horloge.avancer(Duration.ofMinutes(30));
        assertThat(e.joursRestants()).isCloseTo(10.0 - 30.0 / 1440, within(0.001));
        horloge.avancer(Duration.ofMinutes(31));
        assertThat(e.joursRestants()).isCloseTo(4.0 - 61.0 / 1440, within(0.001));

        // Échu : valeur négative.
        horloge.avancer(Duration.ofDays(6));
        assertThat(e.joursRestants()).isNegative();
    }

    @Test
    @DisplayName("N'expire pas : +Inf ; attributs absents : NaN ; annuaire injoignable : dernière valeur gardée")
    void casLimites() throws Exception {
        EcheanceSecretAnnuaire inconnue = echeance(null);
        assertThat(inconnue.joursRestants()).isNaN();

        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, String.valueOf(Long.MAX_VALUE));
        EcheanceSecretAnnuaire jamais = echeance(null);
        assertThat(jamais.joursRestants()).isInfinite().isPositive();

        // Mot de passe à 0 : changement exigé, secret échu.
        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, "0");
        assertThat(echeance(null).joursRestants()).isNegative();

        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, filetime(horloge.instant().plus(Duration.ofDays(20))));
        EcheanceSecretAnnuaire e = echeance(null);
        assertThat(e.joursRestants()).isCloseTo(20.0, within(0.001));
        simulateur.arreter();
        horloge.avancer(Duration.ofHours(2));
        assertThat(e.joursRestants()).isCloseTo(20.0 - 2.0 / 24, within(0.001));
    }

    @Test
    @DisplayName("Échéance déclarée (GED_LDAP_ECHEANCE_SECRET) prioritaire ; date mal formée refusée au démarrage")
    void echeanceDeclaree() throws Exception {
        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, String.valueOf(Long.MAX_VALUE));
        // 2026-10-15 à minuit, heure du Maroc (UTC+1) : 14 j 15 h après le 30/09 à 8 h UTC.
        assertThat(echeance(LocalDate.parse("2026-10-15")).joursRestants()).isCloseTo(14.625, within(0.001));
        assertThat(EcheanceSecretAnnuaire.dateDeclaree("")).isNull();
        assertThatThrownBy(() -> EcheanceSecretAnnuaire.dateDeclaree("15/10/2026"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Publiée à /actuator/prometheus sous ged_annuaire_compte_service_echeance_jours")
    void jaugePrometheus() throws Exception {
        poser(EcheanceSecretAnnuaire.EXPIRATION_MOT_DE_PASSE, filetime(horloge.instant().plus(Duration.ofDays(7))));
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        echeance(null).bindTo(registre);
        assertThat(registre.scrape()).containsPattern("ged_annuaire_compte_service_echeance_jours(\\{[^}]*})? 7\\.0");
    }
}
