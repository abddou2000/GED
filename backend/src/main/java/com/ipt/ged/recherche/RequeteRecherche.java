package com.ipt.ged.recherche;

import java.util.List;

/**
 * Recherche plein texte.
 *
 * @param texte   syntaxe utilisateur de {@code websearch_to_tsquery} :
 *                expressions entre guillemets, {@code -exclusion}, {@code or} ;
 * @param page    rang de page, à partir de 0 ;
 * @param taille  lignes par page (bornée à {@link #TAILLE_MAX}) ;
 * @param filtres critères multicritères combinés en ET avec le plein texte
 *                (fournis par l'appelant sur le modèle documentaire).
 */
public record RequeteRecherche(String texte, int page, int taille, Tri tri, List<FragmentSql> filtres) {

    public static final int TAILLE_MAX = 200;

    public enum Tri {
        /** {@code ts_rank_cd} décroissant. */
        PERTINENCE,
        /** Indexation la plus récente d'abord. */
        INDEXATION_RECENTE
    }

    public RequeteRecherche {
        if (page < 0) throw new IllegalArgumentException("Le rang de page doit être positif ou nul.");
        if (taille < 1) throw new IllegalArgumentException("La taille de page doit être au moins 1.");
        taille = Math.min(taille, TAILLE_MAX);
        tri = tri == null ? Tri.PERTINENCE : tri;
        filtres = filtres == null ? List.of() : List.copyOf(filtres);
    }

    public static RequeteRecherche simple(String texte, int page, int taille) {
        return new RequeteRecherche(texte, page, taille, Tri.PERTINENCE, List.of());
    }
}
