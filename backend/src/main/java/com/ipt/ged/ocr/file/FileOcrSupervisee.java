package com.ipt.ged.ocr.file;

import com.ipt.ged.supervision.FileDeTraitement;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * La file {@code ocr_job} vue par la supervision (§6.7 : « profondeur de la
 * file OCR »).
 *
 * <p>Déclarée comme {@link FileDeTraitement} (point d'extension du lot
 * exploitation) : la sonde {@code filesTraitement}, les métriques
 * {@code ged_file_profondeur{file="ocr"}} et {@code ged_file_age_plus_ancien_seconds}
 * et les alertes Prometheus la prennent en charge sans autre code.
 */
public class FileOcrSupervisee implements FileDeTraitement {

    private final OcrJobQueue file;
    private final Clock horloge;

    public FileOcrSupervisee(OcrJobQueue file, Clock horloge) {
        this.file = file;
        this.horloge = horloge;
    }

    /** Étiquette de métrique stable. */
    @Override
    public String nom() {
        return "ocr";
    }

    /** Jobs en attente ou en cours. */
    @Override
    public long profondeur() {
        return file.profondeur();
    }

    /** Ancienneté du plus ancien dépôt pas encore interrogeable. */
    @Override
    public Optional<Duration> ageDuPlusAncien() {
        return file.plusAncienDepotEnAttente().map(t -> {
            Duration d = Duration.between(t, horloge.instant());
            return d.isNegative() ? Duration.ZERO : d;
        });
    }
}
