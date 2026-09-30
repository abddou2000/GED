package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Échéance du secret du compte de service de l'annuaire (P-02 : « expiration
 * du secret surveillée » ; §6.7 ; ANO-E10-002). Jauge
 * {@code ged_annuaire_compte_service_echeance_jours} : jours restants avant
 * que le compte de service ne puisse plus se lier (négatif : échu), alerte
 * {@code GedAnnuaireSecretCompteServiceEcheance}.
 *
 * <h2>Source</h2>
 * <ul>
 *   <li><b>Échéance déclarée</b> ({@code ged.identite.annuaire.echeance-secret},
 *       {@code GED_LDAP_ECHEANCE_SECRET}, date ISO) : prioritaire. Pour un
 *       secret dont le mot de passe n'expire pas dans l'annuaire mais que la
 *       politique de MMED fait tourner à date fixe.</li>
 *   <li>Sinon, <b>l'entrée du compte de service lui-même</b>, lue par son DN
 *       avec sa propre liaison : {@code msDS-UserPasswordExpiryTimeComputed}
 *       (échéance du mot de passe calculée par Active Directory, stratégies de
 *       mot de passe affinées comprises) et {@code accountExpires} (fin du
 *       compte) ; la plus proche l'emporte. « Jamais » (valeur maximale, ou 0
 *       pour {@code accountExpires}) : {@code +Inf} ; mot de passe à 0
 *       (changement exigé) : échu. Aucun attribut d'un utilisateur n'est lu
 *       (P2, D1).</li>
 * </ul>
 * Échéance inconnue (attributs absents, annuaire jamais joint) : {@code NaN}.
 * Annuaire momentanément injoignable : la dernière valeur lue est gardée.
 *
 * <p>La lecture est gardée une heure : une échéance se compte en jours, la
 * supervision ne doit pas multiplier les liaisons sur les contrôleurs.
 */
@Component
public class EcheanceSecretAnnuaire implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(EcheanceSecretAnnuaire.class);

    /** Échéance du mot de passe calculée par AD (attribut construit, à demander nommément). */
    static final String EXPIRATION_MOT_DE_PASSE = "msDS-UserPasswordExpiryTimeComputed";
    /** Fin de validité du compte. */
    static final String EXPIRATION_COMPTE = "accountExpires";

    static final Duration VALIDITE = Duration.ofHours(1);
    private static final ZoneId FUSEAU = ZoneId.of("Africa/Casablanca");
    /** Secondes entre le 1er janvier 1601 (origine des dates FILETIME d'AD) et le 1er janvier 1970. */
    private static final long ORIGINE_FILETIME = 11_644_473_600L;

    private final ControleursAnnuaire controleurs;
    private final LdapTemplate[] modeles;
    private final String compteService;
    private final LocalDate echeanceDeclaree;
    private final Clock horloge;

    private volatile Optional<Instant> echeance;
    private volatile boolean jamais;
    private volatile Instant luLe = Instant.EPOCH;

    @Autowired
    public EcheanceSecretAnnuaire(ControleursAnnuaire controleurs, ProprietesIdentite proprietes) {
        this(controleurs, proprietes.getAnnuaire().getCompteService(),
                dateDeclaree(proprietes.getAnnuaire().getEcheanceSecret()), Clock.systemUTC());
    }

    /** Échéance déclarée : vide = aucune ; mal formée = refus de démarrer. */
    static LocalDate dateDeclaree(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        try {
            return LocalDate.parse(valeur.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("ged.identite.annuaire.echeance-secret (GED_LDAP_ECHEANCE_SECRET) : "
                    + "date AAAA-MM-JJ attendue, reçu « " + valeur + " ».", e);
        }
    }

    EcheanceSecretAnnuaire(ControleursAnnuaire controleurs, String compteService, LocalDate echeanceDeclaree,
                           Clock horloge) {
        this.controleurs = controleurs;
        this.compteService = compteService == null ? "" : compteService;
        this.echeanceDeclaree = echeanceDeclaree;
        this.horloge = horloge;
        this.modeles = new LdapTemplate[controleurs.controleurs().size()];
        for (ControleursAnnuaire.Controleur c : controleurs.controleurs()) {
            modeles[c.rang()] = new LdapTemplate(c.source());
        }
    }

    /** Jours restants avant l'échéance du secret ; +Inf s'il n'expire pas ; NaN si elle est inconnue. */
    public double joursRestants() {
        Instant maintenant = horloge.instant();
        if (echeanceDeclaree != null) {
            return jours(maintenant, echeanceDeclaree.atStartOfDay(FUSEAU).toInstant());
        }
        if (echeance == null || !maintenant.isBefore(luLe.plus(VALIDITE))) {
            relire(maintenant);
        }
        if (jamais) return Double.POSITIVE_INFINITY;
        Optional<Instant> e = echeance;
        return e == null || e.isEmpty() ? Double.NaN : jours(maintenant, e.get());
    }

    private synchronized void relire(Instant maintenant) {
        if (echeance != null && maintenant.isBefore(luLe.plus(VALIDITE))) return;
        if (compteService.isBlank()) {
            echeance = Optional.empty();
            luLe = maintenant;
            return;
        }
        try {
            Lecture l = controleurs.executer(c -> {
                try {
                    return modeles[c.rang()].lookup(compteService,
                            new String[]{EXPIRATION_MOT_DE_PASSE, EXPIRATION_COMPTE},
                            (AttributesMapper<Lecture>) EcheanceSecretAnnuaire::lecture);
                } catch (org.springframework.ldap.NamingException e) {
                    throw new AnnuaireIndisponibleException(e);
                }
            });
            jamais = l.jamais();
            echeance = l.echeance();
            if (!l.jamais() && l.echeance().isEmpty()) {
                log.warn("Échéance du secret du compte de service illisible ({} et {} absents) : "
                        + "renseigner GED_LDAP_ECHEANCE_SECRET.", EXPIRATION_MOT_DE_PASSE, EXPIRATION_COMPTE);
            }
            luLe = maintenant;
        } catch (AnnuaireIndisponibleException e) {
            // Dernière valeur gardée ; nouvel essai à la prochaine lecture de la jauge.
            if (echeance == null) echeance = Optional.empty();
            log.warn("Échéance du secret du compte de service non relue : annuaire injoignable.");
        }
    }

    /** Résultat d'une lecture : échéance la plus proche, ou « jamais ». */
    record Lecture(Optional<Instant> echeance, boolean jamais) {
    }

    static Lecture lecture(Attributes attributs) throws javax.naming.NamingException {
        Instant plusProche = null;
        boolean lue = false;
        for (String nom : new String[]{EXPIRATION_MOT_DE_PASSE, EXPIRATION_COMPTE}) {
            Attribute a = attributs.get(nom);
            if (a == null || a.get() == null) continue;
            long valeur;
            try {
                valeur = Long.parseLong(a.get().toString().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            lue = true;
            if (valeur == Long.MAX_VALUE) continue;                          // jamais
            if (valeur <= 0 && nom.equals(EXPIRATION_COMPTE)) continue;      // 0 : compte sans fin
            // Mot de passe à 0 : changement exigé à la prochaine liaison, donc échu.
            Instant i = valeur <= 0 ? Instant.EPOCH : depuisFiletime(valeur);
            if (plusProche == null || i.isBefore(plusProche)) plusProche = i;
        }
        return new Lecture(Optional.ofNullable(plusProche), lue && plusProche == null);
    }

    /** Date FILETIME d'Active Directory (centaines de nanosecondes depuis 1601) en instant. */
    static Instant depuisFiletime(long filetime) {
        return Instant.ofEpochSecond(filetime / 10_000_000L - ORIGINE_FILETIME, (filetime % 10_000_000L) * 100);
    }

    private static double jours(Instant maintenant, Instant echeance) {
        return Duration.between(maintenant, echeance).toSeconds() / 86_400.0;
    }

    @Override
    public void bindTo(MeterRegistry registre) {
        Gauge.builder("ged.annuaire.compte.service.echeance.jours", this, EcheanceSecretAnnuaire::joursRestants)
                .description("Jours restants avant l'echeance du secret du compte de service de l'annuaire "
                        + "(P-02) ; +Inf : n'expire pas ; NaN : inconnue")
                .register(registre);
    }
}
