package com.ipt.ged.planindexation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.index.IndexField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture et écriture de la charte de nommage, stockée sous forme de JSON dans
 * une colonne unique — même format que l'application d'origine
 * ({@code {"indexs":[...],"separateur":"_","majuscule":false}}).
 *
 * <p>Le format est conservé tel quel pour qu'un plan créé dans l'une des deux
 * GED reste exploitable par l'autre.
 */
public final class CharteNommage {

    private CharteNommage() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sérialise les jetons ; renvoie {@code null} si la charte est vide. */
    public static String serialiser(List<String> jetons, String separateur, boolean majuscule) {
        if (jetons == null || jetons.isEmpty()) return null;
        Map<String, Object> charte = new LinkedHashMap<>();
        charte.put("indexs", jetons);
        charte.put("separateur", separateur);
        charte.put("majuscule", majuscule);
        try {
            return JSON.writeValueAsString(charte);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Jetons enregistrés, dans l'ordre. Une charte illisible renvoie une liste
     * vide plutôt qu'une exception : un plan mal formé ne doit pas empêcher
     * d'afficher la liste entière.
     */
    public static List<String> jetons(String charteJson) {
        if (charteJson == null || charteJson.isBlank()) return List.of();
        try {
            JsonNode noeud = JSON.readTree(charteJson).get("indexs");
            if (noeud == null || !noeud.isArray()) return List.of();
            return JSON.convertValue(noeud, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Nom composé à partir des jetons : un identifiant renvoie le nom de l'index
     * correspondant, une clé système son libellé (DATE, YEAR…).
     */
    public static String apercu(List<String> jetons, List<IndexField> indices,
                                String separateur, boolean majuscule) {
        List<String> noms = new ArrayList<>();
        for (String jeton : jetons) {
            if (JetonsSysteme.estJeton(jeton)) {
                noms.add(JetonsSysteme.libelle(jeton));
                continue;
            }
            indices.stream()
                    .filter(i -> String.valueOf(i.getId()).equals(jeton))
                    .findFirst()
                    .map(IndexField::getNomIndex)
                    // Index retiré du plan depuis : on garde le jeton brut, ce qui
                    // rend le trou visible au lieu de le faire disparaître.
                    .ifPresentOrElse(noms::add, () -> noms.add(jeton));
        }
        String joint = String.join(separateur == null ? "_" : separateur, noms);
        return majuscule ? joint.toUpperCase() : joint.toLowerCase();
    }
}
