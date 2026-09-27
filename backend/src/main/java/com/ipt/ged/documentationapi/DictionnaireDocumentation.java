package com.ipt.ged.documentationapi;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dictionnaire de la spécification ({@code documentationapi/champs.yml}) :
 * descriptions et exemples des charges utiles, tenus hors des DTO pour ne pas
 * annoter le code des autres lots.
 */
public class DictionnaireDocumentation {

    /** Description et exemple d'un champ ; l'exemple peut être nul (objet référencé). */
    public record Entree(String description, Object exemple) {}

    private final Map<String, String> objets = new LinkedHashMap<>();
    private final Map<String, Entree> champs = new LinkedHashMap<>();
    private final Map<String, Entree> surcharges = new LinkedHashMap<>();
    private final Map<String, String> parametres = new LinkedHashMap<>();
    private final Map<String, Entree> corps = new LinkedHashMap<>();

    public DictionnaireDocumentation() {
        this("documentationapi/champs.yml");
    }

    @SuppressWarnings("unchecked")
    DictionnaireDocumentation(String ressource) {
        Map<String, Object> racine;
        try (InputStream in = new ClassPathResource(ressource).getInputStream()) {
            racine = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Dictionnaire OpenAPI illisible : " + ressource, e);
        }
        ((Map<String, Object>) racine.getOrDefault("objets", Map.of()))
                .forEach((k, v) -> objets.put(k, String.valueOf(v)));
        lireEntrees((Map<String, Object>) racine.getOrDefault("champs", Map.of()), champs);
        lireEntrees((Map<String, Object>) racine.getOrDefault("surcharges", Map.of()), surcharges);
        lireEntrees((Map<String, Object>) racine.getOrDefault("corps", Map.of()), corps);
        ((Map<String, Object>) racine.getOrDefault("parametres", Map.of()))
                .forEach((k, v) -> parametres.put(k, String.valueOf(v)));
    }

    @SuppressWarnings("unchecked")
    private static void lireEntrees(Map<String, Object> source, Map<String, Entree> cible) {
        source.forEach((cle, valeur) -> {
            Map<String, Object> e = (Map<String, Object>) valeur;
            cible.put(cle, new Entree((String) e.get("description"), e.get("exemple")));
        });
    }

    /**
     * Description d'un schéma ; un homonyme renommé par {@link NomsSchemasDistincts}
     * ({@code DocumentResponseRef}) reprend celle de son nom simple ({@code Ref}).
     */
    public Optional<String> objet(String schema) {
        String d = objets.get(schema);
        if (d != null) return Optional.of(d);
        return objets.entrySet().stream()
                .filter(e -> schema.endsWith(e.getKey()) && schema.length() > e.getKey().length()
                        && Character.isUpperCase(e.getKey().charAt(0)))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }

    /** Surcharge « Schema.champ » d'abord, puis l'entrée commune du nom de champ. */
    public Optional<Entree> champ(String schema, String champ) {
        Entree e = surcharges.get(schema + "." + champ);
        return Optional.ofNullable(e != null ? e : champs.get(champ));
    }

    public Optional<String> parametre(String nom) {
        return Optional.ofNullable(parametres.get(nom));
    }

    /** Corps sans schéma nommé, reconnu par la fin du chemin. */
    public Optional<Entree> corps(String chemin) {
        return corps.entrySet().stream().filter(e -> chemin.endsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst();
    }
}
