package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Sonde de santé de l'annuaire (P-02, T-074) : liaison du compte de service sur
 * le premier contrôleur qui répond. Nom de la sonde : {@code annuaire}.
 *
 * <p>Le résultat est gardé 30 secondes : une supervision qui interroge souvent ne
 * doit pas multiplier les liaisons sur les contrôleurs de domaine.
 *
 * <p>Un annuaire indisponible empêche toute NOUVELLE connexion mais pas le
 * travail des sessions ouvertes (§3.3) : l'exploitation doit donc placer cette
 * sonde dans un groupe de supervision, pas dans la sonde de disponibilité
 * ({@code readiness}) qui retirerait l'instance du répartiteur.
 */
@Component("annuaire")
public class SondeAnnuaire implements HealthIndicator {

    private static final Duration VALIDITE = Duration.ofSeconds(30);

    private final Annuaire annuaire;
    private volatile Health dernier;
    private volatile Instant mesureLe = Instant.EPOCH;

    public SondeAnnuaire(Annuaire annuaire) {
        this.annuaire = annuaire;
    }

    @Override
    public Health health() {
        Instant maintenant = Instant.now();
        if (dernier != null && maintenant.isBefore(mesureLe.plus(VALIDITE))) {
            return dernier;
        }
        Health mesure;
        try {
            annuaire.sonder();
            mesure = Health.up().build();
        } catch (AnnuaireIndisponibleException e) {
            mesure = Health.down().withDetail("erreur", "annuaire injoignable").build();
        }
        dernier = mesure;
        mesureLe = maintenant;
        return mesure;
    }
}
