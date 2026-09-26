package com.ipt.ged.recherche;

import java.util.Map;

/**
 * Morceau de SQL paramétré, inséré dans la requête de recherche : prédicat de
 * droits (point d'extension du lot autorisation) ou critère supplémentaire.
 *
 * <p>Le SQL ne contient <b>jamais</b> de valeur concaténée : les valeurs passent
 * par des paramètres nommés. Chaque fournisseur préfixe ses noms de paramètres
 * (ex. {@code droits_utilisateur}) pour ne pas entrer en collision avec ceux de
 * la requête ({@code q}, {@code taille}, {@code decalage}).
 */
public record FragmentSql(String sql, Map<String, Object> parametres) {

    public static final FragmentSql VRAI = new FragmentSql("TRUE", Map.of());
    public static final FragmentSql FAUX = new FragmentSql("FALSE", Map.of());

    public FragmentSql {
        parametres = parametres == null ? Map.of() : Map.copyOf(parametres);
    }
}
