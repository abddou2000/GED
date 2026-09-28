package com.ipt.ged.document.conservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Jour de référence des échéances de conservation : le jour civil de MMED
 * (heure légale du Maroc), le même pour la tâche d'alerte, le filtre de
 * recherche « échéance dépassée » et la mise en évidence à l'écran. Une
 * échéance est dépassée dès le jour même (échéance &lt;= aujourd'hui).
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

    /** L'échéance est-elle atteinte ? (une échéance absente ne l'est jamais) */
    public static boolean depassee(LocalDate echeance) {
        return echeance != null && !echeance.isAfter(aujourdhui());
    }
}
