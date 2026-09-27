package com.ipt.ged.indexation;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.typedocument.TypeDocument;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validation de métadonnées contre le plan d'indexation d'un type (§12.7),
 * <b>sans rien écrire</b> : appartenance au plan, nature de chaque valeur,
 * index obligatoires.
 *
 * <p>Partagée par l'enregistrement des index ({@link IndexationService}) et par
 * le dépôt avec métadonnées (§5.3, §12.11 temps 1), qui doit refuser des
 * métadonnées invalides <b>avant</b> d'écrire le fichier : une seule règle,
 * deux points d'entrée.
 */
public final class ValidationPlan {

    private ValidationPlan() {
    }

    /** Index du plan du type, corbeille exclue ; vide si le type n'a pas de plan. */
    public static List<IndexField> champs(TypeDocument type) {
        PlanIndexation plan = type != null ? type.getPlanIndexation() : null;
        if (plan == null) return List.of();
        return plan.getIndices().stream().filter(i -> !i.isDeleted()).toList();
    }

    /** Le type porte-t-il un plan d'indexation ({@code SANS_PLAN} sinon, §12.11) ? */
    public static boolean aUnPlan(TypeDocument type) {
        return type != null && type.getPlanIndexation() != null;
    }

    /**
     * Erreurs par champ pour un jeu complet de valeurs (dépôt d'un nouveau
     * document : aucune valeur existante).
     *
     * @param valeurs valeur par identifiant d'index (clés déjà résolues) ;
     * @param inconnus clés reçues qui ne désignent aucun index du plan.
     * @return libellé d'erreur par nom de champ ; vide si tout est valide.
     */
    public static Map<String, String> erreurs(List<IndexField> plan, Map<UUID, String> valeurs, List<String> inconnus) {
        Map<String, String> erreurs = new LinkedHashMap<>();
        for (String cle : inconnus) {
            erreurs.put(cle, "n'appartient pas au plan d'indexation de ce type de document.");
        }
        for (IndexField champ : plan) {
            String valeur = valeurs.get(champ.getId());
            valeur = valeur == null ? null : valeur.trim();
            if (valeur == null || valeur.isEmpty()) {
                if (champ.isObligatoire()) erreurs.put(champ.getCode(), "est obligatoire.");
                continue;
            }
            String motif = motifType(champ, valeur);
            if (motif != null) erreurs.put(champ.getCode(), motif);
        }
        return erreurs;
    }

    /** Refuse une valeur incompatible avec la nature de l'index (400). */
    public static void controlerType(IndexField champ, String valeur) {
        if (valeur == null || valeur.isEmpty()) return;
        String motif = motifType(champ, valeur);
        if (motif == null) return;
        if (champ.getFieldType() == com.ipt.ged.index.IndexFieldType.LISTE) {
            throw new IllegalArgumentException(
                    "« " + valeur + " » ne fait pas partie des valeurs de « " + champ.getNomIndex() + " ».");
        }
        throw new IllegalArgumentException("« " + champ.getNomIndex() + " » " + motif);
    }

    /** Pourquoi la valeur ne convient pas à l'index ; {@code null} si elle convient. */
    static String motifType(IndexField champ, String valeur) {
        return switch (champ.getFieldType()) {
            case NOMBRE -> nombre(valeur) == null ? "attend un nombre." : null;
            case DATE -> valeur.matches("\\d{4}-\\d{2}-\\d{2}") ? null : "attend une date (AAAA-MM-JJ).";
            case LISTE -> {
                List<String> options = options(champ);
                yield options.isEmpty() || options.stream().anyMatch(o -> o.equalsIgnoreCase(valeur)) ? null
                        : "n'accepte pas « " + valeur + " » (valeurs : " + String.join(", ", options) + ").";
            }
            case TEXTE -> null;
        };
    }

    /** Valeurs autorisées d'un index de nature liste. */
    static List<String> options(IndexField champ) {
        if (champ.getValeurs() == null) return List.of();
        return Arrays.stream(champ.getValeurs().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Double nombre(String s) {
        try {
            return Double.valueOf(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
