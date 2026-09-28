package com.ipt.ged.planindexation.metamodele;

import com.ipt.ged.index.IndexFieldType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Validation des métadonnées d'un document contre une VERSION de plan
 * (dossier technique §12.7) : appartenance au plan, nature de chaque valeur,
 * caractère obligatoire, liste de valeurs. Aucune écriture : la validation
 * précède tout enregistrement.
 *
 * <p>En sortie, les métadonnées sont NORMALISÉES (voir
 * {@link ValeursMetadonnees}) et indexées par le CODE de l'index : c'est la
 * forme stockée dans {@code document.metadonnees} et lue par la recherche.
 * Un index absent reçoit sa valeur par défaut si le plan en donne une, que le
 * jeu soit complet ou non (ANO-E7-003) : la valeur par défaut est celle d'un
 * index non renseigné, au dépôt en deux temps comme à l'indexation.
 */
public final class ValidateurMetadonnees {

    /** Longueur maximale d'une valeur texte. */
    public static final int TEXTE_MAX = 4000;

    private ValidateurMetadonnees() {}

    /**
     * @param brutes métadonnées reçues, clé = code (insensible à la casse) ou identifiant d'index
     * @return métadonnées normalisées, clé = code de l'index
     * @throws MetadonneesInvalidesException dictionnaire d'erreurs par champ
     */
    public static Map<String, Object> valider(DefinitionPlan plan, Map<String, ?> brutes) {
        return valider(plan, brutes, true);
    }

    /**
     * @param complet vrai : jeu complet de métadonnées, les index obligatoires
     *                sont exigés. Faux : dépôt SANS métadonnées (dépôt en deux
     *                temps, §12.11) ou miroir des valeurs d'index — le document
     *                est à indexer, les obligatoires le seront à l'indexation.
     *                Dans les deux cas, un index absent reçoit sa valeur par défaut.
     */
    public static Map<String, Object> valider(DefinitionPlan plan, Map<String, ?> brutes, boolean complet) {
        Map<String, String> erreurs = new LinkedHashMap<>();
        Map<String, Object> normalisees = new LinkedHashMap<>();
        if (brutes != null) {
            for (Map.Entry<String, ?> e : brutes.entrySet()) {
                Optional<ChampPlan> champ = plan.champ(e.getKey());
                if (champ.isEmpty()) {
                    erreurs.put(e.getKey(), "n'appartient pas au plan d'indexation de ce type de document.");
                    continue;
                }
                Object v = e.getValue();
                if (v == null || (v instanceof String s && s.isBlank())) continue;
                try {
                    normalisees.put(champ.get().code(), normaliser(champ.get(), v));
                } catch (IllegalArgumentException ex) {
                    erreurs.put(champ.get().code(), ex.getMessage());
                }
            }
        }
        for (ChampPlan c : plan.champs()) {
            if (normalisees.containsKey(c.code()) || erreurs.containsKey(c.code())) continue;
            if (c.valeurDefaut() != null && !c.valeurDefaut().isBlank()) {
                try {
                    normalisees.put(c.code(), normaliser(c, c.valeurDefaut()));
                    continue;
                } catch (IllegalArgumentException ignore) {
                    // Valeur par défaut incohérente avec la nature : traitée comme absente.
                }
            }
            if (complet && c.obligatoire()) erreurs.put(c.code(), "est obligatoire.");
        }
        if (!erreurs.isEmpty()) throw new MetadonneesInvalidesException(erreurs);
        return normalisees;
    }

    /** Valeur convertie dans la forme stockée ; {@link IllegalArgumentException} si elle ne convient pas. */
    static Object normaliser(ChampPlan c, Object v) {
        IndexFieldType nature = c.natureTypee();
        if (v instanceof Map<?, ?> || v instanceof Iterable<?>) {
            throw new IllegalArgumentException("attend une valeur simple.");
        }
        switch (nature) {
            case NOMBRE -> {
                BigDecimal n = ValeursMetadonnees.nombre(v);
                if (n == null) throw new IllegalArgumentException("attend un nombre.");
                return n;
            }
            case DATE -> {
                LocalDate d = ValeursMetadonnees.date(v);
                if (d == null) throw new IllegalArgumentException("attend une date (AAAA-MM-JJ).");
                return d.toString();
            }
            case BOOLEEN -> {
                Boolean b = ValeursMetadonnees.booleen(v);
                if (b == null) throw new IllegalArgumentException("attend oui ou non.");
                return b;
            }
            case LISTE -> {
                String s = v.toString().trim();
                if (c.options().isEmpty()) return s;
                return c.options().stream().filter(o -> o.equalsIgnoreCase(s)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("n'accepte pas « " + s + " » (valeurs : "
                                + String.join(", ", c.options()) + ")."));
            }
            default -> {
                String s = v.toString().trim();
                if (s.length() > TEXTE_MAX) {
                    throw new IllegalArgumentException("dépasse " + TEXTE_MAX + " caractères.");
                }
                return s;
            }
        }
    }
}
