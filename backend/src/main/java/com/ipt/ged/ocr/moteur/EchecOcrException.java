package com.ipt.ged.ocr.moteur;

/**
 * Échec du traitement OCR d'un document, avec son motif (§4.3.4).
 *
 * <p>Un échec <b>définitif</b> (fichier corrompu, protégé par mot de passe,
 * format non supporté) ne gagne rien à être rejoué : le job passe directement
 * à {@code OCR_ECHEC}. Un échec transitoire (délai dépassé, moteur
 * indisponible) suit la politique de reprise à 1, 5 puis 30 minutes.
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
