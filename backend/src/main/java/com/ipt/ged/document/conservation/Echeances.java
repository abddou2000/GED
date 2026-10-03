package com.ipt.ged.document.conservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Seule source de l'« aujourd'hui » métier : le jour civil de MMED (heure
 * légale du Maroc), quel que soit le fuseau du serveur. Le même pour la tâche
 * d'alerte, le filtre de recherche « échéance dépassée », la mise en évidence
 * à l'écran, la date du document par défaut au dépôt (donc le point de départ
 * de la conservation), les jetons de date de la charte de nommage et la courbe
 * des dépôts par jour (ANO-F-037). Une échéance est dépassée dès le jour même
 * (échéance &lt;= aujourd'hui).
 *
 * <p>Les horodatages techniques (instants en base, journal d'audit et ses
 * partitions mensuelles en UTC) ne passent pas par ici.
 */
public final class Echeances {

    /** Fuseau de MMED ; la base, elle, travaille en UTC. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");

    private Echeances() {
    }

    public static LocalDate aujourdhui() {
        return aujourdhui(Clock.systemUTC());
    }

    public static LocalDate aujourdhui(Clock horloge) {
        return LocalDate.now(horloge.withZone(ZONE));
    }

    /** Date et heure présentes à Casablanca (jetons « date », « heure »… de la charte de nommage). */
    public static LocalDateTime maintenant(Clock horloge) {
        return LocalDateTime.now(horloge.withZone(ZONE));
    }

    /** L'échéance est-elle atteinte ? (une échéance absente ne l'est jamais) */
    public static boolean depassee(LocalDate echeance) {
        return echeance != null && !echeance.isAfter(aujourdhui());
    }
}
