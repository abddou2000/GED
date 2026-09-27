package com.ipt.ged.ocr.file;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reprises après échec (§4.3.4) : « trois tentatives avec délais croissants
 * (1, 5 puis 30 minutes) ; après échec, le job passe à OCR_ECHEC ».
 *
 * <p>Lecture retenue : la première exécution est suivie d'au plus trois
 * nouvelles tentatives, programmées 1, 5 puis 30 minutes après chaque échec ;
 * l'échec de la troisième nouvelle tentative clôt le job. Les délais sont
 * paramétrables ; leur nombre fixe le nombre de reprises.
 */
public record PolitiqueReprise(List<Duration> delais) {

    public static final PolitiqueReprise PAR_DEFAUT =
            new PolitiqueReprise(List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)));

    public PolitiqueReprise {
        delais = List.copyOf(delais);
    }

    /**
     * @param executionsFaites exécutions déjà commencées, y compris celle qui vient d'échouer.
     * @return délai avant la prochaine tentative, ou vide si les reprises sont épuisées.
     */
    public Optional<Duration> apres(int executionsFaites) {
        int reprise = executionsFaites - 1; // 0 = première reprise
        if (reprise < 0 || reprise >= delais.size()) return Optional.empty();
        return Optional.of(delais.get(reprise));
    }

    /** Nombre maximal d'exécutions d'un job. */
    public int executionsMax() {
        return delais.size() + 1;
    }
}
