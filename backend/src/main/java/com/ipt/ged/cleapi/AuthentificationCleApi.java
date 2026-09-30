package com.ipt.ged.cleapi;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Vérifie une clé d'API présentée dans {@code X-API-Key} (DAT §5.4), dans
 * l'ordre : forme, environnement, existence, secret (comparaison à temps
 * constant), révocation, expiration, application active, adresse source,
 * quotas.
 *
 * <p>Le motif précis d'un refus (expirée, révoquée) n'est donné qu'une fois le
 * secret vérifié : sans le secret, on n'apprend rien de plus qu'« invalide ».
 */
@Service
public class AuthentificationCleApi {

    private final CleApiRepository cles;
    private final QuotasCleApi quotas;
    private final ProprietesCleApi proprietes;
    private final Clock horloge;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthentificationCleApi(CleApiRepository cles, QuotasCleApi quotas, ProprietesCleApi proprietes) {
        this(cles, quotas, proprietes, Clock.systemUTC());
    }

    AuthentificationCleApi(CleApiRepository cles, QuotasCleApi quotas, ProprietesCleApi proprietes, Clock horloge) {
        this.cles = cles;
        this.quotas = quotas;
        this.proprietes = proprietes;
        this.horloge = horloge;
    }

    /** Refus d'authentification : statut, code stable, message, délai éventuel (429). */
    public record Refus(HttpStatus statut, String code, String detail, Duration reessayerApres,
                        String identifiant, java.util.UUID applicationId, String applicationCode) {}

    /** Issue de la vérification : l'application authentifiée, ou le refus. */
    public record Resultat(ApplicationAuthentifiee application, Refus refus) {
        static Resultat ok(ApplicationAuthentifiee a) {
            return new Resultat(a, null);
        }

        static Resultat refus(HttpStatus s, String code, String detail, String identifiant, Application app) {
            return new Resultat(null, new Refus(s, code, detail, Duration.ZERO, identifiant,
                    app == null ? null : app.getId(), app == null ? null : app.getCode()));
        }
    }

    @Transactional(readOnly = true)
    public Resultat verifier(String valeur, String adresseSource) {
        var lue = FormatCleApi.lire(valeur);
        if (lue.isEmpty()) {
            return Resultat.refus(HttpStatus.UNAUTHORIZED, CodesErreurCleApi.CLE_API_INVALIDE,
                    "Clé d'API invalide.", null, null);
        }
        String identifiant = lue.get().identifiant();
        CleApi cle = cles.findByIdentifiant(identifiant).orElse(null);
        if (cle == null || !FormatCleApi.memeEmpreinte(cle.getEmpreinte().trim(),
                FormatCleApi.empreinte(lue.get().secret()))) {
            return Resultat.refus(HttpStatus.UNAUTHORIZED, CodesErreurCleApi.CLE_API_INVALIDE,
                    "Clé d'API invalide.", identifiant, null);
        }
        Application app = cle.getApplication();
        if (!proprietes.environnement().equals(lue.get().environnement())
                || !cle.getEnvironnement().equals(lue.get().environnement())) {
            return Resultat.refus(HttpStatus.UNAUTHORIZED, CodesErreurCleApi.CLE_API_AUTRE_ENVIRONNEMENT,
                    "Cette clé a été émise pour un autre environnement.", identifiant, app);
        }
        Instant maintenant = horloge.instant();
        if (cle.revoquee()) {
            return Resultat.refus(HttpStatus.UNAUTHORIZED, CodesErreurCleApi.CLE_API_REVOQUEE,
                    "Clé d'API révoquée.", identifiant, app);
        }
        if (cle.expiree(maintenant)) {
            return Resultat.refus(HttpStatus.UNAUTHORIZED, CodesErreurCleApi.CLE_API_EXPIREE,
                    "Clé d'API expirée : demandez une nouvelle clé à l'Administrateur.", identifiant, app);
        }
        if (!app.isActive()) {
            return Resultat.refus(HttpStatus.FORBIDDEN, CodesErreurCleApi.APPLICATION_DESACTIVEE,
                    "Application désactivée.", identifiant, app);
        }
        if (!adresseAutorisee(app.adresses(), adresseSource)) {
            return Resultat.refus(HttpStatus.FORBIDDEN, CodesErreurCleApi.ADRESSE_NON_AUTORISEE,
                    "Adresse source non autorisée pour cette application.", identifiant, app);
        }
        QuotasCleApi.Decision quota = quotas.consommer(cle, app.getQuotaMinute(), app.getQuotaJour());
        if (!quota.accepte()) {
            return new Resultat(null, new Refus(HttpStatus.TOO_MANY_REQUESTS, quota.code(),
                    "Quota d'appels dépassé pour cette clé.", quota.reessayerApres(), identifiant, app.getId(),
                    app.getCode()));
        }
        return Resultat.ok(new ApplicationAuthentifiee(app.getId(), app.getCode(), cle.getId(), cle.isDelegation()));
    }

    static boolean adresseAutorisee(List<String> autorisees, String adresse) {
        if (autorisees.isEmpty()) return true;
        if (adresse == null) return false;
        for (String a : autorisees) {
            try {
                if (new IpAddressMatcher(a).matches(adresse)) return true;
            } catch (IllegalArgumentException e) {
                // Entrée illisible : ne donne aucun accès.
            }
        }
        return false;
    }
}
