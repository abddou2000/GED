package com.ipt.ged.ocr.file;

/**
 * Priorité d'un job OCR dans la file {@code ocr_job} (colonne {@code priorite},
 * la plus petite d'abord).
 *
 * <p>La reprise du fonds existant (~150 000 documents, plusieurs semaines de
 * calcul) partage la file avec le flux courant. Sans priorité, la file étant
 * servie dans l'ordre de dépôt, chaque dépôt courant attendrait la fin de la
 * reprise et le délai de disponibilité de 24 h (revue client D6, §4.3.4) serait
 * violé pendant toute sa durée (risque R31, essais de charge §3.3). Le flux
 * courant passe donc toujours avant la reprise ; la reprise avance quand le
 * flux laisse des workers libres (le flux courant occupe ~1 h 20 de calcul par
 * jour sur 4 vCPU).
 */
public enum PrioriteOcr {

    /** Dépôt, nouvelle version, restauration : délai D6 à tenir. */
    FLUX_COURANT(0),
    /** Reprise du fonds existant ({@code RepriseVersionsEnClair}) : passe après le flux. */
    REPRISE(1);

    private final int code;

    PrioriteOcr(int code) {
        this.code = code;
    }

    /** Valeur de la colonne {@code ocr_job.priorite}. */
    public int code() {
        return code;
    }
}
