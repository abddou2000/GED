package com.ipt.ged.supervision;

import java.time.Duration;
import java.util.Optional;

/**
 * Une file de traitements de fond à superviser (DAT 6.7 : « profondeur de la
 * file OCR »). Point d'extension : chaque lot qui crée une file (OCR en E6,
 * archivage et retypage en E7, notifications en E8) déclare un bean qui
 * implémente cette interface, et obtient sans autre code :
 * <ul>
 *   <li>sa place dans la sonde de santé {@code filesTraitement} ;</li>
 *   <li>les métriques {@code ged_file_profondeur{file="…"}} et
 *       {@code ged_file_age_plus_ancien_seconds{file="…"}} ;</li>
 *   <li>les alertes correspondantes de {@code deploiement/prometheus/alertes.yml}.</li>
 * </ul>
 *
 * <p>Les implémentations interrogent la base (par exemple
 * {@code SELECT count(*) FROM ocr_job WHERE statut = 'EN_ATTENTE'}) : elles
 * doivent rester légères, elles sont appelées à chaque collecte.
 */
public interface FileDeTraitement {

    /** Nom court et stable de la file, utilisé comme étiquette de métrique : {@code ocr}. */
    String nom();

    /** Nombre de traitements en attente ou en cours. */
    long profondeur();

    /** Âge du plus ancien traitement en attente, s'il y en a un. */
    default Optional<Duration> ageDuPlusAncien() {
        return Optional.empty();
    }
}
