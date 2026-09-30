package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Décision D15 (risque R28) : lecture ponctuelle de {@code userAccountControl}
 * au moment d'une délégation, par le compte de service, avec un cache court.
 * Vérifié contre le simulateur d'Active Directory (UnboundID) chargé de
 * {@code annuaire-test.ldif} (Omar Tazi : 514, les autres : 512).
 */
class EtatCompteAnnuaireTest {

    private static final String BASE = "DC=marchicamed,DC=ma";
    private static final UUID GUID_SARA = UUID.fromString("5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e01");
    private static final UUID GUID_KARIM = UUID.fromString("5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e02");
    private static final UUID GUID_OMAR = UUID.fromString("5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e05");
    private static final String DN_KARIM = "CN=Karim El Fassi,OU=Utilisateurs,DC=marchicamed,DC=ma";

    private static SimulateurAnnuaire simulateur;

    @BeforeAll
    static void demarrer() throws Exception {
        try (InputStream ldif = EtatCompteAnnuaireTest.class.getResourceAsStream("/annuaire/annuaire-test.ldif")) {
            simulateur = SimulateurAnnuaire.demarrer(BASE, 0, ldif);
        }
    }

    @AfterAll
    static void arreter() {
        simulateur.arreter();
    }

    private static EtatCompteAnnuaireLdap lecteur(String url) {
        ProprietesIdentite p = new ProprietesIdentite();
        p.getAnnuaire().setUrls(List.of(url));
        p.getAnnuaire().setBase(BASE);
        p.getAnnuaire().setCompteService("CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma");
        p.getAnnuaire().setMotDePasseService("test-only-password");
        p.getAnnuaire().setExigerLdaps(false);
        p.getAnnuaire().setPool(false);
        p.getAnnuaire().setDelaiConnexion(Duration.ofSeconds(1));
        p.getAnnuaire().setDelaiLecture(Duration.ofSeconds(2));
        ConfigurationAnnuaire config = new ConfigurationAnnuaire();
        return config.etatCompteAnnuaire(config.controleursAnnuaire(p), p);
    }

    @Test
    @DisplayName("Bit ACCOUNTDISABLE (0x2) : 512 actif, 514 désactivé, objectGUID absent introuvable")
    void lectureDuBit() {
        EtatCompteAnnuaireLdap l = lecteur(simulateur.url());
        assertThat(l.etat(GUID_SARA)).isEqualTo(EtatCompteAnnuaire.Etat.ACTIF);
        assertThat(l.etat(GUID_OMAR)).isEqualTo(EtatCompteAnnuaire.Etat.DESACTIVE);
        assertThat(l.etat(UUID.randomUUID())).isEqualTo(EtatCompteAnnuaire.Etat.INTROUVABLE);
    }

    @Test
    @DisplayName("Limitée à ce contrôle : seul userAccountControl est demandé ; attribut absent = indéterminé")
    void seulAttributDemande() throws Exception {
        EtatCompteAnnuaireLdap l = lecteur(simulateur.url());
        assertThat(l.attributsDemandes()).containsExactly("userAccountControl");
        try {
            simulateur.serveur().modify(DN_KARIM, new Modification(ModificationType.DELETE, "userAccountControl"));
            assertThat(l.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.INDETERMINE);
            simulateur.serveur().modify(DN_KARIM,
                    new Modification(ModificationType.ADD, "userAccountControl", "66050")); // 0x10202 : désactivé
            assertThat(l.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.DESACTIVE);
        } finally {
            simulateur.serveur().modify(DN_KARIM,
                    new Modification(ModificationType.REPLACE, "userAccountControl", "512"));
        }
        assertThat(l.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.ACTIF);
    }

    @Test
    @DisplayName("Annuaire injoignable : exception, jamais un état inventé")
    void annuaireInjoignable() throws Exception {
        String morte;
        try (ServerSocket s = new ServerSocket(0)) {
            morte = "ldap://localhost:" + s.getLocalPort();
        }
        EtatCompteAnnuaireLdap l = lecteur(morte);
        assertThatThrownBy(() -> l.etat(GUID_SARA)).isInstanceOf(AnnuaireIndisponibleException.class);
    }

    /** Source comptée, pour observer les relectures du cache. */
    private static final class SourceComptee implements EtatCompteAnnuaire {
        final List<UUID> lectures = new ArrayList<>();
        Etat reponse = Etat.ACTIF;

        @Override
        public Etat etat(UUID objectGuid) {
            lectures.add(objectGuid);
            return reponse;
        }
    }

    /** Horloge avançable à la main. */
    private static final class Horloge extends Clock {
        final AtomicReference<Instant> maintenant = new AtomicReference<>(Instant.parse("2026-09-30T10:00:00Z"));

        void avancer(Duration d) {
            maintenant.updateAndGet(i -> i.plus(d));
        }

        @Override public Instant instant() { return maintenant.get(); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    @DisplayName("Cache court : état réutilisé pendant sa durée, relu ensuite ; une désactivation est vue au plus tard à l'expiration")
    void cacheCourt() {
        SourceComptee source = new SourceComptee();
        Horloge h = new Horloge();
        EtatCompteEnCache cache = new EtatCompteEnCache(source, Duration.ofMinutes(2), h);

        assertThat(cache.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.ACTIF);
        source.reponse = EtatCompteAnnuaire.Etat.DESACTIVE;
        h.avancer(Duration.ofSeconds(119));
        assertThat(cache.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.ACTIF);
        assertThat(source.lectures).hasSize(1);
        h.avancer(Duration.ofSeconds(1));
        assertThat(cache.etat(GUID_KARIM)).isEqualTo(EtatCompteAnnuaire.Etat.DESACTIVE);
        assertThat(source.lectures).hasSize(2);
    }

    @Test
    @DisplayName("Cache : l'état indéterminé n'est jamais gardé ; durée 0 = relecture à chaque appel")
    void indetermineEtDureeNulle() {
        SourceComptee source = new SourceComptee();
        source.reponse = EtatCompteAnnuaire.Etat.INDETERMINE;
        EtatCompteEnCache cache = new EtatCompteEnCache(source, Duration.ofMinutes(2), new Horloge());
        cache.etat(GUID_KARIM);
        cache.etat(GUID_KARIM);
        assertThat(source.lectures).hasSize(2);

        SourceComptee s2 = new SourceComptee();
        EtatCompteEnCache sansCache = new EtatCompteEnCache(s2, Duration.ZERO, new Horloge());
        sansCache.etat(GUID_KARIM);
        sansCache.etat(GUID_KARIM);
        assertThat(s2.lectures).hasSize(2);
    }

    @Test
    @DisplayName("D15 : un cache de plus de 5 minutes (ou négatif) empêche le démarrage")
    void dureeBornee() {
        SourceComptee source = new SourceComptee();
        assertThat(new EtatCompteEnCache(source, Duration.ofMinutes(5), new Horloge()).duree())
                .isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> new EtatCompteEnCache(source, Duration.ofMinutes(5).plusSeconds(1), new Horloge()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("D15");
        assertThatThrownBy(() -> new EtatCompteEnCache(source, Duration.ofSeconds(-1), new Horloge()))
                .isInstanceOf(IllegalStateException.class);
    }
}
