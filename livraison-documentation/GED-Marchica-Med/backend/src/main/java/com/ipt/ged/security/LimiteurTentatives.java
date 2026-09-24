package com.ipt.ged.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frein sur les tentatives de connexion ratées.
 *
 * <p>Sans lui, BCrypt et un mot de passe fort ne servent à rien : rien
 * n'empêche d'essayer des millions de combinaisons. Après un nombre donné
 * d'échecs, l'e-mail visé est refusé pendant quelques minutes, quelle que soit
 * la validité du mot de passe proposé.
 *
 * <p>Le comptage est en mémoire : il retombe à zéro au redémarrage et n'est pas
 * partagé entre plusieurs instances. C'est une limite assumée — le jour où
 * l'application tournera derrière plusieurs nœuds, ce compteur devra vivre dans
 * un cache partagé (Redis) pour rester efficace.
 */
@Component
public class LimiteurTentatives {

    private record Compteur(int echecs, Instant dernier) {
    }

    private final Map<String, Compteur> compteurs = new ConcurrentHashMap<>();
    private final int maximum;
    private final Duration blocage;

    public LimiteurTentatives(
            @Value("${ged.securite.tentatives-max:8}") int maximum,
            @Value("${ged.securite.blocage-minutes:10}") long blocageMinutes) {
        this.maximum = maximum;
        this.blocage = Duration.ofMinutes(blocageMinutes);
    }

    private String cle(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** Vrai si l'e-mail est en période de blocage. */
    public boolean estBloque(String email) {
        Compteur c = compteurs.get(cle(email));
        if (c == null) return false;
        if (Instant.now().isAfter(c.dernier().plus(blocage))) {
            compteurs.remove(cle(email));   // la fenêtre est passée, on oublie
            return false;
        }
        return c.echecs() >= maximum;
    }

    /** Minutes restantes avant de pouvoir réessayer. */
    public long minutesRestantes(String email) {
        Compteur c = compteurs.get(cle(email));
        if (c == null) return 0;
        long secondes = Duration.between(Instant.now(), c.dernier().plus(blocage)).toSeconds();
        return Math.max(1, (secondes + 59) / 60);
    }

    public void echec(String email) {
        compteurs.compute(cle(email), (k, c) -> {
            if (c == null || Instant.now().isAfter(c.dernier().plus(blocage))) {
                return new Compteur(1, Instant.now());
            }
            return new Compteur(c.echecs() + 1, Instant.now());
        });
    }

    /** Une connexion réussie efface l'ardoise. */
    public void succes(String email) {
        compteurs.remove(cle(email));
    }
}
