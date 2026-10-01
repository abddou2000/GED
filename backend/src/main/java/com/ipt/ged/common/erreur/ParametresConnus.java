package com.ipt.ged.common.erreur;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

/**
 * Paramètres de requête (chaîne de requête) connus d'un point d'entrée : tout
 * autre nom est ignoré, comme le veut la politique de compatibilité (DAT
 * §5.3.2, P-08), et signalé dans l'en-tête {@value ChampsIgnores#ENTETE}
 * (ANO-F-011). Pendant, pour les paramètres d'URL, de {@link ChampsInconnusSignales}.
 */
public final class ParametresConnus {

    private ParametresConnus() {}

    /** Signale chaque paramètre de {@code requete} absent de {@code connus}. */
    public static void signaler(HttpServletRequest requete, HttpServletResponse reponse, Set<String> connus) {
        for (String nom : requete.getParameterMap().keySet()) {
            if (!connus.contains(nom)) {
                ChampsIgnores.signaler(requete, reponse, nom);
            }
        }
    }
}
