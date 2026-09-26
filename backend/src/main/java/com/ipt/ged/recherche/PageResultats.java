package com.ipt.ged.recherche;

import java.util.List;
import java.util.UUID;

/**
 * Page de résultats, total limité au périmètre autorisé.
 *
 * <p>L'extrait est rendu en <b>segments</b> et non en HTML : le texte vient de
 * l'OCR de documents déposés, il peut contenir n'importe quoi (y compris
 * {@code <script>}). Le client affiche chaque segment comme du texte et
 * surligne ceux marqués — aucun HTML à assainir (OWASP A03).
 */
public record PageResultats(List<Resultat> resultats, long total, int page, int taille) {

    public record Resultat(UUID documentId, UUID versionId, double pertinence, List<Segment> extrait) {
    }

    public record Segment(String texte, boolean surligne) {
    }
}
