package com.ipt.ged.identite.annuaire;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache court de l'état des comptes (décision D15 : « cache court de quelques
 * minutes au plus ») : une application qui délègue plusieurs appels de suite
 * pour la même personne ne relit pas l'annuaire à chaque appel. Un compte
 * désactivé dans l'AD est donc refusé au plus tard {@link #MAXIMUM} après la
 * désactivation.
 *
 * <p>Seuls les résultats certains sont gardés (actif, désactivé, introuvable) ;
 * un état indéterminé et une panne de l'annuaire ne le sont jamais.
 */
public class EtatCompteEnCache implements EtatCompteAnnuaire {

    /** Durée maximale admise : au-delà, D15 n'est plus respectée. */
    public static final Duration MAXIMUM = Duration.ofMinutes(5);

    private record Entree(Etat etat, Instant expire) {}

    private final EtatCompteAnnuaire source;
    private final Duration duree;
    private final Clock horloge;
    private final Map<UUID, Entree> entrees = new ConcurrentHashMap<>();

    public EtatCompteEnCache(EtatCompteAnnuaire source, Duration duree, Clock horloge) {
        if (duree == null || duree.isNegative() || duree.compareTo(MAXIMUM) > 0) {
            throw new IllegalStateException("Cache de l'état des comptes : " + duree
                    + " hors de [0, 5 min] (décision D15, ged.api.delegation.cache-etat-compte).");
        }
        this.source = source;
        this.duree = duree;
        this.horloge = horloge;
    }

    @Override
    public Etat etat(UUID objectGuid) {
        Instant maintenant = horloge.instant();
        Entree e = entrees.get(objectGuid);
        if (e != null && maintenant.isBefore(e.expire())) return e.etat();
        Etat lu = source.etat(objectGuid);
        if (lu != Etat.INDETERMINE && !duree.isZero()) {
            entrees.put(objectGuid, new Entree(lu, maintenant.plus(duree)));
            // Purge paresseuse : la table ne garde que des entrées vivantes.
            if (entrees.size() > 10_000) entrees.values().removeIf(x -> !maintenant.isBefore(x.expire()));
        } else {
            entrees.remove(objectGuid);
        }
        return lu;
    }

    /** Oublie l'état d'un compte (tests, ou après une intervention de l'exploitant). */
    public void oublier(UUID objectGuid) {
        entrees.remove(objectGuid);
    }

    public Duration duree() {
        return duree;
    }
}
