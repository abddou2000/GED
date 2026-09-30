package com.ipt.ged.common.erreur;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.TreeSet;

/**
 * Paramètres de requête (chaîne de requête) acceptés par un point d'entrée :
 * tout autre nom est refusé en 400 {@link CodesErreur#PARAMETRE_INCONNU}
 * au lieu d'être ignoré (ANO-F-011). Pendant, pour les paramètres d'URL, de
 * {@link ChampsInconnusRefuses}.
 */
public final class ParametresConnus {

    private ParametresConnus() {}

    /** @throws RequeteInvalideException au premier paramètre hors de {@code acceptes}. */
    public static void exiger(HttpServletRequest requete, Set<String> acceptes) {
        for (String nom : requete.getParameterMap().keySet()) {
            if (!acceptes.contains(nom)) {
                throw new RequeteInvalideException(CodesErreur.PARAMETRE_INCONNU,
                        "Paramètre inconnu : « " + nom + " ». Paramètres acceptés : "
                                + String.join(", ", new TreeSet<>(acceptes)) + ".").avec("parametre", nom);
            }
        }
    }
}
