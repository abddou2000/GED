package com.ipt.ged.recherche;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Page de résultats, total limité au périmètre autorisé.
 *
 * <p>L'extrait est rendu en <b>segments</b> et non en HTML : le texte vient de
 * l'OCR de documents déposés, il peut contenir n'importe quoi (y compris
 * {@code <script>}). Le client affiche chaque segment comme du texte et
 * surligne ceux marqués — aucun HTML à assainir (OWASP A03).
 *
 * @param totalPlafonne vrai quand les correspondances dépassent le plafond de
 *                      la recherche plein texte : {@code total} vaut alors ce
 *                      plafond et se lit « plus de {@code total} résultats »
 *                      (affiner la recherche) ; faux : total exact.
 */
public record PageResultats(List<Resultat> resultats, long total, int page, int taille, boolean totalPlafonne) {

    /**
     * Taille de page appliquée sous son nom du §5.3.2 ({@code size}, comme les
     * autres pages de l'API) ; même valeur que {@code taille}, gardé (T-050).
     */
    @JsonProperty("size")
    public int size() {
        return taille;
    }

    /** Page au total exact. */
    public PageResultats(List<Resultat> resultats, long total, int page, int taille) {
        this(resultats, total, page, taille, false);
    }

    /**
     * @param nom          nom du document ;
     * @param typeDocument libellé du type documentaire ;
     * @param espace       dossier (espace) du document ;
     * @param deposeLe     date de dépôt.
     */
    public record Resultat(UUID documentId, UUID versionId, double pertinence, List<Segment> extrait,
                           String nom, String typeDocument, String espace, java.time.Instant deposeLe,
                           /** {@code ACTIF} ou {@code ARCHIVE} : badge de statut (§12.6). */
                           String statutConservation,
                           /** Canal du dépôt (T-040). */
                           String canalDepot,
                           /** Échéance de conservation atteinte : mise en évidence (§12.9). */
                           boolean echeanceDepassee,
                           /** Date du document, clé de tri {@code DATE_DOCUMENT} (§12.7, P-21). */
                           java.time.LocalDate dateDocument) {

        public Resultat(UUID documentId, UUID versionId, double pertinence, List<Segment> extrait, String nom,
                        String typeDocument, String espace, java.time.Instant deposeLe, String statutConservation,
                        String canalDepot) {
            this(documentId, versionId, pertinence, extrait, nom, typeDocument, espace, deposeLe, statutConservation,
                    canalDepot, false, null);
        }
    }

    public record Segment(String texte, boolean surligne) {
    }
}
