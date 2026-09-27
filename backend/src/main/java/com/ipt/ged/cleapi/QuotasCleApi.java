package com.ipt.ged.cleapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quotas d'appel par clé (DAT §5.4) : 600 requêtes par minute et 100 000 par
 * jour par défaut, réglables par application ; dépassement : HTTP 429 avec
 * {@code Retry-After}.
 *
 * <p>Compteurs tenus en mémoire (aucune écriture en base par requête) et
 * persistés périodiquement : le compteur du jour survit à un redémarrage ; celui
 * de la minute, non (au pire une minute de quota offerte au redémarrage). Jour
 * et minute en UTC, fenêtres fixes.
 *
 * <p>Limite assumée : chaque instance du back-end compte pour elle-même. Le
 * dimensionnement retenu (DAT §6.6) prévoit une instance ; au-delà, les
 * quotas s'entendent par instance.
 */
@Component
public class QuotasCleApi {

    private static final Logger journal = LoggerFactory.getLogger(QuotasCleApi.class);

    private final JdbcTemplate jdbc;
    private final Clock horloge;
    private final Map<UUID, Compteur> compteurs = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public QuotasCleApi(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    QuotasCleApi(JdbcTemplate jdbc, Clock horloge) {
        this.jdbc = jdbc;
        this.horloge = horloge;
    }

    /** Résultat d'une consommation : accepté, ou refusé avec le code et le délai avant nouvel essai. */
    public record Decision(boolean accepte, String code, Duration reessayerApres) {
        static final Decision OK = new Decision(true, null, Duration.ZERO);
    }

    /** Consomme un appel de la clé ; refuse sans consommer si un quota est atteint. */
    public Decision consommer(CleApi cle, int quotaMinute, int quotaJour) {
        Instant maintenant = horloge.instant();
        Compteur c = compteurs.computeIfAbsent(cle.getId(), id -> new Compteur(cle));
        synchronized (c) {
            long minute = maintenant.getEpochSecond() / 60;
            LocalDate jour = LocalDate.ofInstant(maintenant, ZoneOffset.UTC);
            if (c.minute != minute) {
                c.minute = minute;
                c.appelsMinute = 0;
            }
            if (!jour.equals(c.jour)) {
                c.jour = jour;
                c.appelsJour = 0;
            }
            if (c.appelsJour >= quotaJour) {
                Instant demain = jour.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                return new Decision(false, CodesErreurCleApi.QUOTA_JOUR_DEPASSE, Duration.between(maintenant, demain));
            }
            if (c.appelsMinute >= quotaMinute) {
                Instant minuteSuivante = Instant.ofEpochSecond((minute + 1) * 60);
                return new Decision(false, CodesErreurCleApi.QUOTA_MINUTE_DEPASSE,
                        Duration.between(maintenant, minuteSuivante));
            }
            c.appelsMinute++;
            c.appelsJour++;
            c.derniereUtilisation = maintenant;
            c.modifie = true;
            return Decision.OK;
        }
    }

    /** Persiste les compteurs modifiés (compteur du jour, dernière utilisation). */
    @Scheduled(fixedDelayString = "${ged.api.cles.persistance-compteurs:PT1M}")
    public void persister() {
        for (Map.Entry<UUID, Compteur> e : compteurs.entrySet()) {
            Compteur c = e.getValue();
            LocalDate jour;
            long appels;
            Instant derniere;
            synchronized (c) {
                if (!c.modifie) continue;
                jour = c.jour;
                appels = c.appelsJour;
                derniere = c.derniereUtilisation;
                c.modifie = false;
            }
            try {
                jdbc.update("UPDATE cle_api SET quota_jour_date = ?, quota_jour_appels = ?, derniere_utilisation = ?"
                                + " WHERE id = ?",
                        Date.valueOf(jour), appels, derniere == null ? null : Timestamp.from(derniere), e.getKey());
            } catch (RuntimeException ex) {
                synchronized (c) {
                    c.modifie = true;
                }
                journal.warn("Persistance du compteur de quota de la clé {} impossible", e.getKey(), ex);
            }
        }
    }

    /** Oublie le compteur d'une clé (révocation) ; persiste d'abord. */
    void oublier(UUID cleId) {
        persister();
        compteurs.remove(cleId);
    }

    /** Appels comptés aujourd'hui pour une clé (écran d'administration). */
    long appelsDuJour(CleApi cle) {
        Compteur c = compteurs.get(cle.getId());
        LocalDate aujourdhui = LocalDate.ofInstant(horloge.instant(), ZoneOffset.UTC);
        if (c != null) {
            synchronized (c) {
                return aujourdhui.equals(c.jour) ? c.appelsJour : 0;
            }
        }
        return aujourdhui.equals(cle.getQuotaJourDate()) ? cle.getQuotaJourAppels() : 0;
    }

    Instant derniereUtilisation(CleApi cle) {
        Compteur c = compteurs.get(cle.getId());
        if (c != null && c.derniereUtilisation != null) return c.derniereUtilisation;
        return cle.getDerniereUtilisation();
    }

    /** État d'une clé, initialisé depuis la base (compteur du jour persisté). */
    private final class Compteur {
        long minute = -1;
        int appelsMinute;
        LocalDate jour;
        long appelsJour;
        Instant derniereUtilisation;
        boolean modifie;

        Compteur(CleApi cle) {
            LocalDate aujourdhui = LocalDate.ofInstant(horloge.instant(), ZoneOffset.UTC);
            this.jour = aujourdhui;
            this.appelsJour = aujourdhui.equals(cle.getQuotaJourDate()) ? cle.getQuotaJourAppels() : 0;
            this.derniereUtilisation = cle.getDerniereUtilisation() == null ? null
                    : cle.getDerniereUtilisation().truncatedTo(ChronoUnit.MILLIS);
        }
    }
}
