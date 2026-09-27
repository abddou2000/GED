package com.ipt.ged.ocr.file;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * La file {@code ocr_job} vue par la supervision (§6.7 : « profondeur de la
 * file OCR »).
 *
 * <p>Adaptateur prévu pour le point d'extension {@code FileDeTraitement} du lot
 * exploitation (dev2, {@code com.ipt.ged.supervision}) : mêmes méthodes, même
 * nom de file. Après la fusion de ce lot, il suffit d'ajouter
 * {@code implements FileDeTraitement} : la sonde {@code filesTraitement}, les
 * métriques {@code ged_file_profondeur{file="ocr"}} et les alertes de dev2 le
 * prennent alors en charge, et {@link MetriquesOcr} cesse de publier ses
 * propres jauges de file.
 */
public class FileOcrSupervisee {

    private final OcrJobQueue file;
    private final Clock horloge;

    public FileOcrSupervisee(OcrJobQueue file, Clock horloge) {
        this.file = file;
        this.horloge = horloge;
    }

    /** Étiquette de métrique stable. */
    public String nom() {
        return "ocr";
    }

    /** Jobs en attente ou en cours. */
    public long profondeur() {
        return file.profondeur();
    }

    /** Ancienneté du plus ancien dépôt pas encore interrogeable. */
    public Optional<Duration> ageDuPlusAncien() {
        return file.plusAncienDepotEnAttente().map(t -> {
            Duration d = Duration.between(t, horloge.instant());
            return d.isNegative() ? Duration.ZERO : d;
        });
    }
}
