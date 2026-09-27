package com.ipt.ged.ocr.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paramètres de la chaîne OCR asynchrone et de la recherche plein texte
 * ({@code ged.ocr.chaine.*}, lot E6). Documentés dans {@code application.yml}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ged.ocr.chaine")
public class ProprietesChaineOcr {

    /**
     * Workers, jauges et points d'entrée actifs. Faux tant que les tables
     * ocr_job et document_texte n'existent pas (intégration du lot dev1).
     */
    private boolean actif = false;
    /** Nombre de workers de cette instance. */
    private int workers = 2;
    /** Attente entre deux scrutations d'une file vide. */
    private Duration scrutation = Duration.ofSeconds(5);
    /** Bail posé à la réservation, prolongé à chaque page. */
    private Duration bail = Duration.ofMinutes(10);
    /** Délai maximal par page (§4.3.4). */
    private Duration delaiParPage = Duration.ofSeconds(60);
    /** Délais des reprises : leur nombre fixe le nombre de reprises (§4.3.4 : 1, 5 puis 30 min). */
    private List<Duration> delaisReprise = new ArrayList<>(List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)));
    /** Résolution du rendu des pages scannées. */
    private int dpi = 300;
    /** En deçà, la couche texte d'une page PDF est jugée absente : la page passe à l'OCR. */
    private int seuilCaracteresParPage = 25;
    /** Objectif dépôt → disponibilité en recherche (décision D6 : 24 h). */
    private Duration objectifDisponibilite = Duration.ofHours(24);
    /** Langue par défaut et réglage par code de type documentaire. */
    private String langueDefaut = "fra+ara";
    private Map<String, String> languesParType = new LinkedHashMap<>();
    /** Réindexation complète : taille des lots et pause entre lots. */
    private int reindexationLot = 500;
    private Duration reindexationPause = Duration.ofMillis(200);
}
