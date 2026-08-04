package com.ipt.ged.indexation.dto;

import java.util.List;

/**
 * Proposition d'indexation automatique pour un document — <b>rien n'est écrit</b>.
 *
 * <p>Le nom du fichier est découpé selon la charte du plan (séparateur, ordre des
 * index, casse) et chaque segment est confronté au type de l'index visé. Le
 * résultat est soumis à l'opérateur, qui corrige puis confirme : la validation
 * humaine reste obligatoire avant tout enregistrement.
 */
public record AnalyseResponse(
        Long documentId,
        String fichier,
        String planNom,
        String separateur,
        boolean majuscule,
        boolean modeAutomatique,
        List<String> segments,
        List<Proposition> propositions,
        String referenceProposee,
        int nbReconnus,
        int nbAttendus,
        /** Message à afficher quand l'analyse ne peut pas aboutir seule ; null sinon. */
        String avertissement,
        /** COUCHE_TEXTE, OCR ou AUCUNE — comment le contenu a été lu, s'il l'a été. */
        String provenanceTexte,
        /** Diagnostic de la chaîne d'OCRisation, affiché à l'opérateur. */
        String detailTexte
) {
    /** Une valeur proposée pour un index, avec le verdict du contrôle de type. */
    public record Proposition(
            Long indexFieldId,
            String code,
            String libelle,
            String fieldType,
            List<String> options,
            /** Valeur déduite ; null si rien d'exploitable. */
            String valeurProposee,
            /** D'où vient la proposition : NOM_FICHIER, CONTENU, ou null si aucune. */
            String source,
            /** Valeur déjà enregistrée sur le document ; null si le champ est vierge. */
            String valeurActuelle,
            boolean reconnue,
            /** Pourquoi la proposition n'est pas retenue ; null si elle l'est. */
            String motif
    ) {}
}
