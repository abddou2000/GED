package com.ipt.ged.documentationapi;

import io.swagger.v3.core.jackson.TypeNameResolver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noms des schémas OpenAPI sans collision.
 *
 * <p>springdoc nomme un schéma par le nom simple de la classe : deux charges
 * utiles de lots différents qui s'appellent pareil ({@code Resultat} de
 * l'archivage et de la recherche, par exemple) seraient FUSIONNÉES en un seul
 * schéma, faux pour l'une des deux. Ici, le premier type rencontré garde son
 * nom simple (celui du dictionnaire {@code champs.yml}) ; un homonyme reçoit le
 * nom de sa classe englobante en préfixe ({@code PageResultatsResultat}), ou
 * de son paquet à défaut. Aucun DTO des autres lots n'est renommé.
 */
public class NomsSchemasDistincts extends TypeNameResolver {

    private final Map<String, Class<?>> attribues = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> noms = new ConcurrentHashMap<>();

    @Override
    protected String getNameOfClass(Class<?> classe) {
        String deja = noms.get(classe);
        if (deja != null) return deja;
        String simple = super.getNameOfClass(classe);
        Class<?> titulaire = attribues.putIfAbsent(simple, classe);
        String nom = titulaire == null || titulaire.equals(classe) ? simple : distinct(classe, simple);
        noms.put(classe, nom);
        return nom;
    }

    private String distinct(Class<?> classe, String simple) {
        Class<?> englobante = classe.getEnclosingClass();
        String prefixe = englobante != null ? englobante.getSimpleName()
                : Character.toUpperCase(dernierSegment(classe.getPackageName()).charAt(0))
                  + dernierSegment(classe.getPackageName()).substring(1);
        String nom = prefixe + simple;
        attribues.putIfAbsent(nom, classe);
        return nom;
    }

    private static String dernierSegment(String paquet) {
        int i = paquet.lastIndexOf('.');
        return i < 0 ? paquet : paquet.substring(i + 1);
    }
}
