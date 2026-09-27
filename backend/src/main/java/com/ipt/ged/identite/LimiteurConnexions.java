package com.ipt.ged.identite;

import com.ipt.ged.identite.erreur.TropDeTentativesException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitation de débit de la connexion (dossier technique §3.4.1) : au plus 5
 * tentatives par minute <b>par adresse IP</b> et <b>par identifiant</b>, en
 * fenêtre glissante. Au-delà : 429 avec {@code Retry-After}.
 *
 * <p>Ce n'est pas un verrouillage de compte — celui-là reste la politique de
 * l'annuaire (le back-end ne tient pas de compteur d'échecs propre) — mais un
 * frein qui rend la force brute inopérante depuis la GED. Toutes les tentatives
 * comptent, réussies ou non ; une tentative refusée par le frein ne compte pas,
 * pour que le délai annoncé soit exact.
 *
 * <p>Mémoire bornée : les clés dont la dernière tentative est sortie de la
 * fenêtre sont purgées dès que la table dépasse {@link #SEUIL_PURGE} entrées
 * (l'ancien limiteur grossissait sans fin). Compteurs en mémoire, propres à une
 * instance : à déplacer dans un cache partagé si la GED tourne un jour sur
 * plusieurs nœuds.
 */
@Component
public class LimiteurConnexions {

    static final int SEUIL_PURGE = 10_000;

    private final Map<String, Deque<Instant>> tentatives = new ConcurrentHashMap<>();
    private final int maximum;
    private final Duration fenetre;

    public LimiteurConnexions(ProprietesIdentite proprietes) {
        this.maximum = proprietes.getLimitation().getTentatives();
        this.fenetre = proprietes.getLimitation().getFenetre();
    }

    /**
     * Enregistre une tentative pour l'IP et l'identifiant, ou la refuse si l'un
     * des deux a épuisé son quota.
     *
     * @throws TropDeTentativesException avec le délai d'attente en secondes
     */
    public void consommer(String adresseIp, String identifiant) {
        Instant maintenant = Instant.now();
        String cleIp = "ip:" + (adresseIp == null ? "" : adresseIp);
        String cleId = "id:" + (identifiant == null ? "" : identifiant.trim().toLowerCase(Locale.ROOT));
        synchronized (this) {
            long attente = Math.max(attente(cleIp, maintenant), attente(cleId, maintenant));
            if (attente > 0) {
                throw new TropDeTentativesException(attente);
            }
            ajouter(cleIp, maintenant);
            ajouter(cleId, maintenant);
            if (tentatives.size() > SEUIL_PURGE) {
                purger(maintenant);
            }
        }
    }

    /** Secondes à attendre pour cette clé ; 0 si une tentative est permise. */
    private long attente(String cle, Instant maintenant) {
        Deque<Instant> file = tentatives.get(cle);
        if (file == null) return 0;
        oublierAnciennes(file, maintenant);
        if (file.size() < maximum) return 0;
        Instant liberation = file.peekFirst().plus(fenetre);
        long secondes = (Duration.between(maintenant, liberation).toMillis() + 999) / 1000;
        return Math.max(1, secondes);
    }

    private void ajouter(String cle, Instant maintenant) {
        tentatives.computeIfAbsent(cle, k -> new ArrayDeque<>()).addLast(maintenant);
    }

    private void oublierAnciennes(Deque<Instant> file, Instant maintenant) {
        Instant limite = maintenant.minus(fenetre);
        while (!file.isEmpty() && !file.peekFirst().isAfter(limite)) {
            file.pollFirst();
        }
    }

    private void purger(Instant maintenant) {
        tentatives.values().forEach(f -> oublierAnciennes(f, maintenant));
        tentatives.values().removeIf(Deque::isEmpty);
    }

    /** Taille courante de la table, pour les tests de bornage. */
    int taille() {
        return tentatives.size();
    }

    /** Remise à zéro : réservée aux tests. */
    void vider() {
        tentatives.clear();
    }
}
