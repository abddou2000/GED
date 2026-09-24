package com.ipt.ged.indexation.dto;

import java.util.List;

/**
 * Index déduits d'un nom de fichier, avant tout dépôt.
 *
 * <p>Plus pauvre que {@code AnalyseResponse} — pas de référence composée ni de
 * valeurs déjà enregistrées, le document n'existant pas encore. En revanche la
 * PROVENANCE du texte et l'avertissement de lecture y figurent : le serveur
 * ouvre réellement le fichier quand le nom ne suffit pas, OCR compris. Les
 * taire revenait à faire passer une reconnaissance optique — faillible par
 * nature — pour une donnée sûre, sans que l'opérateur sache qu'il devait
 * relire.</p>
 *
 * @param planNom    nom du plan appliqué, {@code null} si le type n'en a pas
 * @param separateur séparateur attendu dans le nom du fichier
 * @param champs     un élément par index du plan, dans l'ordre du plan
 * @param nbReconnus nombre de champs que le nom du fichier a suffi à remplir
 * @param nbAttendus nombre total d'index du plan
 * @param nomPropose nom que porterait le document s'il était déposé maintenant,
 *                   {@code null} si la charte est manuelle ou si rien n'est
 *                   déductible
 * @param avertissement ce que la lecture n'a pas pu conclure, {@code null} si
 *                   tout a été déduit
 * @param provenanceTexte {@code COUCHE_TEXTE}, {@code OCR} ou {@code AUCUNE} —
 *                   comment le contenu a été lu, s'il l'a été
 */
public record ApercuResponse(
        String planNom,
        String separateur,
        List<AnalyseResponse.Proposition> champs,
        int nbReconnus,
        int nbAttendus,
        String nomPropose,
        String avertissement,
        String provenanceTexte) {

    /** Le type ne porte aucun plan : il n'y a pas de champ à afficher. */
    public static ApercuResponse sansPlan() {
        return new ApercuResponse(null, "_", List.of(), 0, 0, null, null, "AUCUNE");
    }
}
