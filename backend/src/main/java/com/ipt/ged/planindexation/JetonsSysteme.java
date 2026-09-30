package com.ipt.ged.planindexation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jetons de nommage qui ne correspondent à aucun index en base : ils sont
 * remplacés à la volée au moment du dépôt (date du jour, année…).
 *
 * <p>Les <b>clés</b> ({@code date}, {@code houres}…) sont reprises telles quelles
 * de l'application d'origine : elles sont enregistrées dans la charte, et un
 * plan exporté d'une GED doit rester lisible par l'autre. Les <b>libellés</b>,
 * seuls affichés (liste des jetons, aperçu du nommage), sont en français
 * (§5, ANO-F-019).
 */
public final class JetonsSysteme {

    private JetonsSysteme() {}

    private static final Map<String, String> LIBELLES = new LinkedHashMap<>();

    static {
        LIBELLES.put("date", "DATE");
        LIBELLES.put("houres", "HEURE");
        LIBELLES.put("months", "MOIS");
        LIBELLES.put("days", "JOUR");
        LIBELLES.put("year", "ANNÉE");
    }

    public static Map<String, String> libelles() {
        return LIBELLES;
    }

    public static boolean estJeton(String cle) {
        return LIBELLES.containsKey(cle);
    }

    public static String libelle(String cle) {
        return LIBELLES.get(cle);
    }

    /**
     * Valeur du jeton à un instant donné, au format de l'application d'origine :
     * année sur deux chiffres, mois et jour sur deux chiffres.
     *
     * @return {@code null} si la clé n'est pas un jeton système
     */
    public static String valeur(String cle, LocalDateTime instant) {
        if (cle == null) return null;
        return switch (cle) {
            case "date" -> String.format("%02d%02d%02d",
                    instant.getYear() % 100, instant.getMonthValue(), instant.getDayOfMonth());
            case "houres" -> String.format("%02d%02d%02d",
                    instant.getHour(), instant.getMinute(), instant.getSecond());
            case "months" -> String.format("%02d", instant.getMonthValue());
            case "days" -> String.format("%02d", instant.getDayOfMonth());
            case "year" -> String.format("%02d", instant.getYear() % 100);
            default -> null;
        };
    }
}
