package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.identite.erreur.IdentifiantsRefusesException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bascule entre contrôleurs de domaine (D4, ANO-E2-002) : ordre d'essai, mise à
 * l'écart d'un contrôleur en panne, refus jamais rejoué ailleurs.
 */
class ControleursAnnuaireTest {

    /** Horloge réglable. */
    private static final class Horloge extends Clock {
        private Instant maintenant = Instant.parse("2026-09-30T08:00:00Z");

        void avancer(Duration d) {
            maintenant = maintenant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return maintenant;
        }
    }

    private final Horloge horloge = new Horloge();

    private ControleursAnnuaire controleurs(String... urls) {
        List<ControleursAnnuaire.Controleur> l = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) l.add(new ControleursAnnuaire.Controleur(i, urls[i], null));
        return new ControleursAnnuaire(l, Duration.ofSeconds(30), horloge);
    }

    @Test
    @DisplayName("Premier en panne : le suivant répond, le premier passe en fin de liste 30 s puis redevient prioritaire")
    void miseALEcart() {
        ControleursAnnuaire c = controleurs("dc1", "dc2", "dc3");
        List<String> essais = new ArrayList<>();
        String r = c.executer(k -> {
            essais.add(k.url());
            if (k.url().equals("dc1")) throw new AnnuaireIndisponibleException(new RuntimeException("muet"));
            return k.url();
        });
        assertThat(r).isEqualTo("dc2");
        assertThat(essais).containsExactly("dc1", "dc2");
        assertThat(c.ordre()).extracting(ControleursAnnuaire.Controleur::url).containsExactly("dc2", "dc3", "dc1");

        essais.clear();
        String second = c.executer(k -> {
            essais.add(k.url());
            return k.url();
        });
        assertThat(second).isEqualTo("dc2");
        assertThat(essais).containsExactly("dc2");

        horloge.avancer(Duration.ofSeconds(31));
        assertThat(c.ordre()).extracting(ControleursAnnuaire.Controleur::url).containsExactly("dc1", "dc2", "dc3");
        // Il répond de nouveau : il reste prioritaire.
        String retour = c.executer(ControleursAnnuaire.Controleur::url);
        assertThat(retour).isEqualTo("dc1");
        assertThat(c.ordre()).extracting(ControleursAnnuaire.Controleur::url).containsExactly("dc1", "dc2", "dc3");
    }

    @Test
    @DisplayName("Tous en panne : chacun essayé une fois, dernière erreur rendue ; à l'écart, ils restent essayés")
    void tousEnPanne() {
        ControleursAnnuaire c = controleurs("dc1", "dc2");
        List<String> essais = new ArrayList<>();
        for (int tour = 0; tour < 2; tour++) {
            assertThatThrownBy(() -> c.executer(k -> {
                essais.add(k.url());
                throw new AnnuaireIndisponibleException(new RuntimeException(k.url()));
            })).isInstanceOf(AnnuaireIndisponibleException.class);
        }
        assertThat(essais).containsExactly("dc1", "dc2", "dc1", "dc2");
    }

    @Test
    @DisplayName("Un refus (mot de passe faux) n'est jamais rejoué sur un autre contrôleur ni compté comme panne")
    void refusNonRejoue() {
        ControleursAnnuaire c = controleurs("dc1", "dc2");
        List<String> essais = new ArrayList<>();
        assertThatThrownBy(() -> c.executer(k -> {
            essais.add(k.url());
            throw new IdentifiantsRefusesException();
        })).isInstanceOf(IdentifiantsRefusesException.class);
        assertThat(essais).containsExactly("dc1");
        assertThat(c.ordre()).extracting(ControleursAnnuaire.Controleur::url).containsExactly("dc1", "dc2");
    }
}
