package com.ipt.ged.document.conservation;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.stats.StatistiquesPerimetre;
import com.ipt.ged.stats.StatsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ANO-F-037 : l'« aujourd'hui » métier est celui de Casablanca quel que soit le
 * fuseau de la JVM. Les deux fuseaux extrêmes encadrent Casablanca (UTC+1) :
 * à tout instant, l'un des deux au moins est à une autre date qu'elle, si bien
 * qu'un calcul fait dans le fuseau du serveur échoue toujours sur l'un d'eux.
 */
class JourMetierTest {

    private static final List<String> FUSEAUX_EXTREMES = List.of("Etc/GMT+12", "Pacific/Kiritimati");

    /** Valeur calculée sous chaque fuseau extrême, comparée au jour de Casablanca. */
    private static void sousChaqueFuseau(String quoi, Supplier<LocalDate> calcul) {
        TimeZone initial = TimeZone.getDefault();
        try {
            for (String fuseau : FUSEAUX_EXTREMES) {
                TimeZone.setDefault(TimeZone.getTimeZone(fuseau));
                LocalDate avant = Echeances.aujourdhui();
                LocalDate obtenu = calcul.get();
                LocalDate apres = Echeances.aujourdhui();
                // Avant / après : un passage de minuit pendant le calcul n'est pas une erreur.
                assertTrue(List.of(avant, apres).contains(obtenu),
                        quoi + " sous " + fuseau + " : " + obtenu + " au lieu du jour de Casablanca " + avant);
            }
        } finally {
            TimeZone.setDefault(initial);
        }
    }

    @Test
    @DisplayName("Date du document initiale d'une fiche neuve : jour de Casablanca")
    void dateDocumentInitiale() {
        sousChaqueFuseau("date du document initiale", () -> new UploadDocument("fiche").getDateDocument());
    }

    @Test
    @DisplayName("Courbe des dépôts par jour : son dernier jour est celui de Casablanca")
    void courbeDesDepots() {
        StatistiquesPerimetre documents = mock(StatistiquesPerimetre.class);
        when(documents.datesDeCreationDepuis(any(Instant.class))).thenReturn(List.of());
        StatsController stats = new StatsController(null, documents, null, null, null, null);
        sousChaqueFuseau("dernier jour de la courbe des dépôts", () -> {
            List<StatsController.Point> courbe = stats.depots(7);
            return courbe.get(courbe.size() - 1).date();
        });
    }
}
