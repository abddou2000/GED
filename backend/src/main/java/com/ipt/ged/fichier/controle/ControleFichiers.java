package com.ipt.ged.fichier.controle;

import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.StockageChiffre;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Chaîne de contrôle au dépôt (§6.1.5), dans l'ordre imposé :
 * <ol>
 *   <li><b>taille</b> — la moins coûteuse, elle écarte d'abord ce qui ne sera
 *       jamais accepté (413) ;</li>
 *   <li><b>type réel</b> par le contenu, contre la liste blanche du type
 *       documentaire (415) ;</li>
 *   <li><b>antivirus</b>, en échec fermé (422 {@code FICHIER_INFECTE}, 503 si
 *       ClamAV ne répond pas) ;</li>
 *   <li><b>écriture chiffrée</b> et calcul de l'empreinte.</li>
 * </ol>
 * Tout ce qui peut refuser passe avant l'écriture : un fichier refusé n'a
 * jamais existé dans le référentiel, même chiffré.
 */
public class ControleFichiers {

    private static final long MO = 1024L * 1024L;

    private final DetecteurTypeReel detecteur;
    private final AnalyseurAntivirus antivirus;
    private final StockageChiffre stockage;
    private final ApplicationEventPublisher evenements;
    private final long tailleDefautOctets;
    private final long plafondOctets;
    private final List<String> formatsParDefaut;

    public ControleFichiers(DetecteurTypeReel detecteur, AnalyseurAntivirus antivirus, StockageChiffre stockage,
                            ApplicationEventPublisher evenements, int tailleDefautMo, int plafondMo,
                            List<String> formatsParDefaut) {
        if (tailleDefautMo > plafondMo) {
            throw new IllegalStateException("La taille par défaut (" + tailleDefautMo
                    + " Mo) dépasse le plafond de plateforme (" + plafondMo + " Mo).");
        }
        this.detecteur = detecteur;
        this.antivirus = antivirus;
        this.stockage = stockage;
        this.evenements = evenements;
        this.tailleDefautOctets = tailleDefautMo * MO;
        this.plafondOctets = plafondMo * MO;
        this.formatsParDefaut = List.copyOf(formatsParDefaut);
    }

    /**
     * Règles d'un type documentaire.
     *
     * @param tailleMaxMo taille paramétrée sur le type ; {@code null} ou ≤ 0 →
     *                    taille par défaut (100 Mo). Toujours ramenée sous le
     *                    plafond de plateforme (200 Mo) : un paramétrage ne
     *                    peut pas l'outrepasser.
     * @param formats     extensions paramétrées ; vide → liste par défaut du
     *                    dossier technique.
     */
    public ReglesDepot regles(Integer tailleMaxMo, Collection<String> formats) {
        long taille = (tailleMaxMo == null || tailleMaxMo <= 0) ? tailleDefautOctets : tailleMaxMo * MO;
        Collection<String> retenus = (formats == null || formats.isEmpty()) ? formatsParDefaut : formats;
        return new ReglesDepot(Math.min(taille, plafondOctets), FormatsReconnus.typesAdmis(retenus), List.copyOf(retenus));
    }

    /**
     * Contrôles seuls, sans écriture (aperçu d'indexation qui lit le fichier
     * avant le dépôt).
     *
     * @return le type réel détecté.
     */
    public String controler(SourceFichier source, ReglesDepot regles) {
        if (source.taille() > regles.tailleMaxOctets()) {
            throw Refus.tropVolumineux(regles.tailleMaxOctets());
        }
        String type = detecteur.detecter(source);
        if (!regles.admet(type)) {
            throw Refus.formatNonAutorise(type);
        }
        try {
            antivirus.analyser(source);
        } catch (ErreurFichierException e) {
            if (CodesErreurFichier.FICHIER_INFECTE.equals(e.code())) {
                evenements.publishEvent(new FichierInfecte(source.nomOrigine(), e.getMessage(), Instant.now()));
            }
            throw e;
        }
        return type;
    }

    /**
     * Taille et type réel seulement, sans antivirus : pour un fichier qui n'est
     * ni conservé ni ouvert (aperçu d'indexation, qui ne lit que son nom depuis
     * le cloisonnement §4.3.3) mais qui doit être refusé comme le dépôt le
     * refuserait.
     *
     * @return le type réel détecté.
     */
    public String verifierTailleEtType(SourceFichier source, ReglesDepot regles) {
        if (source.taille() > regles.tailleMaxOctets()) {
            throw Refus.tropVolumineux(regles.tailleMaxOctets());
        }
        String type = detecteur.detecter(source);
        if (!regles.admet(type)) {
            throw Refus.formatNonAutorise(type);
        }
        return type;
    }

    /** Contrôles puis écriture chiffrée ; rien n'est écrit si un contrôle refuse. */
    public Depot deposer(SourceFichier source, ReglesDepot regles) {
        String type = controler(source, regles);
        try (var flux = source.ouvrir()) {
            return new Depot(stockage.ecrire(flux, regles.tailleMaxOctets()), type);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Lecture du fichier déposé impossible", e);
        }
    }

    /** Fichier prêt à être rattaché à une version de document. */
    public record Depot(StockageChiffre.ResultatStockage stockage, String typeMime) {
    }

    /**
     * Événement d'audit : fichier infecté refusé (§6.1.5). Résultat {@code REFUS} ;
     * l'acteur et l'adresse sont ceux de la requête (complétés par le journal).
     */
    public record FichierInfecte(String nomOrigine, String detail, Instant refuseLe)
            implements com.ipt.ged.audit.EvenementAudit {
        @Override
        public String action() {
            return "FICHIER_INFECTE";
        }

        @Override
        public String objetType() {
            return "FICHIER";
        }

        @Override
        public com.ipt.ged.audit.ResultatAudit resultat() {
            return com.ipt.ged.audit.ResultatAudit.REFUS;
        }

        @Override
        public String motif() {
            return detail;
        }

        @Override
        public java.util.Map<String, Object> apres() {
            return java.util.Map.of("fichier", nomOrigine == null ? "" : nomOrigine);
        }
    }
}
