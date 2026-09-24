package com.ipt.ged.indexation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lecture des dates écrites dans un nom de fichier.
 *
 * <p>Ces cas fixent une frontière : ce que la GED accepte de déduire seule, et
 * ce qu'elle refuse de deviner. Le second point compte autant que le premier —
 * une date fausse déposée dans un index se propage ensuite à la recherche et
 * aux exports, sans que personne ne sache d'où elle vient.</p>
 */
class NormalisationDateTest {

    @ParameterizedTest(name = "« {0} » se lit {1}")
    @CsvSource({
            // Déjà ISO : rendu tel quel.
            "2026-08-28, 2026-08-28",
            "2026/08/28, 2026-08-28",
            // Écriture française, la plus courante dans les noms de fichiers.
            "28/08/2026, 2026-08-28",
            "28-08-2026, 2026-08-28",
            "28.08.2026, 2026-08-28",
            // Huit chiffres collés : l'année se reconnaît à sa position.
            "20260828, 2026-08-28",
            "28082026, 2026-08-28",
    })
    @DisplayName("les écritures usuelles sont ramenées en AAAA-MM-JJ")
    void lectureDesFormatsUsuels(String segment, String attendu) {
        assertThat(IndexationService.normaliserDate(segment)).isEqualTo(attendu);
    }

    @ParameterizedTest(name = "« {0} » n'est pas déduite")
    @ValueSource(strings = {
            /* Année sur deux chiffres : « 260805 » se lit 2026-08-05 comme
               2005-08-26. Rien dans le nom ne tranche, donc on ne tranche pas.
               C'est le cas du fichier « 89898965_260805_26_05_08_143434 » :
               la date sera cherchée dans le contenu du document, pas devinée. */
            "260805",
            "28/08/26",
            "050826",
            // Date impossible : refusée, surtout pas « corrigée ».
            "2026-02-31",
            "31/02/2026",
            "20261345",
            // Ce qui n'est pas une date.
            "89898965",
            "143434",
            "",
            "Decompte",
    })
    @DisplayName("ce qui demande de deviner n'est pas déduit")
    void refuseDeDeviner(String segment) {
        assertThat(IndexationService.normaliserDate(segment)).isNull();
    }

    @Test
    @DisplayName("un segment absent ne fait pas échouer la lecture")
    void segmentNul() {
        assertThat(IndexationService.normaliserDate(null)).isNull();
    }
}
