package com.ipt.ged.ocr.moteur;

/**
 * Échec du traitement OCR d'un document, avec son motif (§4.3.4).
 *
 * <p>Le motif dit si l'échec est a priori <b>définitif</b> (fichier corrompu,
 * protégé par mot de passe, format non supporté) ou transitoire (délai
 * dépassé, moteur indisponible). Information de diagnostic seulement : le
 * worker applique à tous la politique de reprise du §4.3.4 (1, 5 puis
 * 30 minutes, puis {@code OCR_ECHEC}), le dossier ne prévoyant pas
 * d'exception (T-034).
 */
public class EchecOcrException extends Exception {

    public enum Motif {
        FICHIER_CORROMPU(true),
        PROTEGE_PAR_MOT_DE_PASSE(true),
        FORMAT_NON_SUPPORTE(true),
        LANGUE_NON_INSTALLEE(true),
        FICHIER_INTROUVABLE(true),
        DELAI_DEPASSE(false),
        MOTEUR_INDISPONIBLE(false),
        ERREUR_MOTEUR(false);

        private final boolean definitif;

        Motif(boolean definitif) {
            this.definitif = definitif;
        }

        public boolean definitif() {
            return definitif;
        }
    }

    private final Motif motif;

    public EchecOcrException(Motif motif, String detail) {
        super(detail);
        this.motif = motif;
    }

    public EchecOcrException(Motif motif, String detail, Throwable cause) {
        super(detail, cause);
        this.motif = motif;
    }

    public Motif motif() {
        return motif;
    }

    public boolean definitif() {
        return motif.definitif();
    }

    /** Libellé enregistré dans {@code ocr_job.motif_echec} et affiché en supervision. */
    public String libelle() {
        return motif.name() + " : " + getMessage();
    }
}
