package com.ipt.ged.indexation.dto;

import java.util.UUID;
import java.util.List;

/**
 * Descripteur d'un critère de recherche, <b>généré depuis un index</b> et non codé
 * en dur : c'est ce qui rend la recherche automatique. Tout index coché
 * « indexé pour recherche » produit un critère ; le front en déduit le contrôle à
 * afficher (saisie, plage de dates, intervalle numérique ou menu déroulant).
 *
 * @param fieldType TEXTE · NOMBRE · DATE · LISTE
 * @param options   valeurs autorisées, uniquement pour le type LISTE
 * @param groupage  vrai si l'index sert aussi à regrouper les résultats
 */
public record CritereResponse(
    UUID id,
    String code,
    String libelle,
    String fieldType,
    List<String> options,
    boolean groupage
) {}
