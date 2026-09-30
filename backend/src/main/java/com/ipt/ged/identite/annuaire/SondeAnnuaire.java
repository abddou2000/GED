package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

import javax.naming.directory.DirContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Sonde de santé de l'annuaire (P-02, T-074) : liaison du compte de service.
 * Nom de la sonde : {@code annuaire}.
 *
 * <h2>Mesure par contrôleur de domaine (décision D4)</h2>
 * <p>Un seul contrôleur aujourd'hui, N demain. Avec plusieurs contrôleurs
 * déclarés ({@code GED_LDAP_URLS}), chacun est lié séparément, avec les mêmes
 * réglages et le même compte de service que les connexions
 * ({@link ConfigurationAnnuaire#construire}, sans pool : la sonde doit
 * réellement ouvrir la liaison) :
 * <ul>
 *   <li>tous répondent : {@code UP} ;</li>
 *   <li>au moins un répond, pas tous : {@link #DEGRADE} — les connexions
 *       fonctionnent (bascule de {@link ControleursAnnuaire}), la redondance est perdue ;</li>
 *   <li>aucun : {@code DOWN}, nouvelles connexions impossibles.</li>
 * </ul>
 * Avec un seul contrôleur, la liaison passe par la source principale
 * ({@link Annuaire#sonder()}), comme avant. Jauge
 * {@code ged_annuaire_controleur{controleur}} : 1 = répond, 0 = muet (alerte
 * {@code GedAnnuaireControleurIndisponible}).
 *
 * <p>Le résultat est gardé 30 secondes : une supervision qui interroge souvent ne
 * doit pas multiplier les liaisons sur les contrôleurs de domaine.
 *
 * <p>Un annuaire indisponible empêche toute NOUVELLE connexion mais pas le
 * travail des sessions ouvertes (§3.3) : l'exploitation doit donc placer cette
 * sonde dans un groupe de supervision, pas dans la sonde de disponibilité
 * ({@code readiness}) qui retirerait l'instance du répartiteur.
 */
@Component("annuaireHealthIndicator")
public class SondeAnnuaire implements HealthIndicator, MeterBinder {

    /** Au moins un contrôleur répond, pas tous : service rendu, redondance perdue. */
    public static final Status DEGRADE = new Status("DEGRADE", "Au moins un contrôleur de domaine ne répond pas");

    private static final Duration VALIDITE = Duration.ofSeconds(30);

    private final Annuaire annuaire;
    /** Liaison d'essai par contrôleur déclaré, dans l'ordre de préférence. */
    private final Map<String, BooleanSupplier> controleurs = new LinkedHashMap<>();
    private final Clock horloge;
    private volatile Health dernier;
    private volatile Map<String, Boolean> derniersEtats = Map.of();
    private volatile Instant mesureLe = Instant.EPOCH;

    @Autowired
    public SondeAnnuaire(Annuaire annuaire, ProprietesIdentite proprietes) {
        this(annuaire, liaisons(proprietes.getAnnuaire()), Clock.systemUTC());
    }

    SondeAnnuaire(Annuaire annuaire, Map<String, BooleanSupplier> controleurs, Clock horloge) {
        this.annuaire = annuaire;
        this.controleurs.putAll(controleurs);
        this.horloge = horloge;
    }

    /** Une liaison d'essai par contrôleur, mêmes réglages que la source principale. */
    private static Map<String, BooleanSupplier> liaisons(ProprietesIdentite.Annuaire a) {
        Map<String, BooleanSupplier> m = new LinkedHashMap<>();
        for (String url : a.getUrls().stream().map(String::trim).filter(u -> !u.isEmpty()).toList()) {
            m.put(url, () -> {
                try {
                    LdapContextSource source = ConfigurationAnnuaire.construire(a, List.of(url), false);
                    DirContext ctx = source.getReadOnlyContext();
                    ctx.close();
                    return true;
                } catch (RuntimeException | javax.naming.NamingException e) {
                    return false;
                }
            });
        }
        return m;
    }

    @Override
    public Health health() {
        Health d = dernier;
        if (d != null && horloge.instant().isBefore(mesureLe.plus(VALIDITE))) return d;
        return mesurer();
    }

    private synchronized Health mesurer() {
        Instant maintenant = horloge.instant();
        if (dernier != null && maintenant.isBefore(mesureLe.plus(VALIDITE))) return dernier;
        Health mesure;
        Map<String, Boolean> etats = new LinkedHashMap<>();
        if (controleurs.size() > 1) {
            controleurs.forEach((url, liaison) -> etats.put(url, liaison.getAsBoolean()));
            long repondent = etats.values().stream().filter(Boolean::booleanValue).count();
            Health.Builder b = repondent == etats.size() ? Health.up()
                    : repondent == 0 ? Health.down().withDetail("erreur", "annuaire injoignable")
                    : Health.status(DEGRADE);
            Map<String, String> detail = new LinkedHashMap<>();
            etats.forEach((url, ok) -> detail.put(url, ok ? "UP" : "DOWN"));
            mesure = b.withDetail("controleurs", detail).build();
        } else {
            boolean ok;
            try {
                annuaire.sonder();
                ok = true;
            } catch (AnnuaireIndisponibleException e) {
                ok = false;
            }
            final boolean joignable = ok;
            controleurs.keySet().forEach(url -> etats.put(url, joignable));
            mesure = ok ? Health.up().build() : Health.down().withDetail("erreur", "annuaire injoignable").build();
        }
        derniersEtats = Map.copyOf(etats);
        dernier = mesure;
        mesureLe = maintenant;
        return mesure;
    }

    /** État du contrôleur à la dernière mesure (relancée si elle est périmée) ; NaN s'il est inconnu. */
    double etatControleur(String url) {
        health();
        Boolean ok = derniersEtats.get(url);
        return ok == null ? Double.NaN : ok ? 1.0 : 0.0;
    }

    @Override
    public void bindTo(MeterRegistry registre) {
        for (String url : controleurs.keySet()) {
            Gauge.builder("ged.annuaire.controleur", this, s -> s.etatControleur(url))
                    .description("Controleur de domaine joignable par le compte de service : 1 = oui, 0 = non (D4)")
                    .tag("controleur", url)
                    .register(registre);
        }
    }
}
