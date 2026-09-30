package com.ipt.ged.planindexation.metamodele;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Lecture des valeurs de métadonnées selon leur nature (§12.7), commune au
 * dépôt, à la modification et à la recherche : une seule règle de conversion.
 *
 * <p>Forme stockée dans {@code document.metadonnees} : texte et liste en
 * chaîne, nombre en nombre JSON, date en chaîne ISO {@code AAAA-MM-JJ}, booléen
 * en booléen JSON — ce que lisent les fonctions SQL {@code meta_texte},
 * {@code meta_nombre} et {@code meta_date}.
 */
public final class ValeursMetadonnees {

    private static final Set<String> VRAI = Set.of("true", "vrai", "oui", "1", "o", "yes");
    private static final Set<String> FAUX = Set.of("false", "faux", "non", "0", "n", "no");

    private ValeursMetadonnees() {}

    /**
     * Formes textuelles (minuscules) que {@link #booleen} lit comme {@code valeur} :
     * la recherche sur index les compare en SQL, avec la même règle.
     */
    public static Set<String> formes(boolean valeur) {
        return valeur ? VRAI : FAUX;
    }

    /** Booléen lu d'un texte usuel (oui / non, vrai / faux, 1 / 0) ; {@code null} si illisible. */
    public static Boolean booleen(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if (VRAI.contains(s)) return true;
        if (FAUX.contains(s)) return false;
        return null;
    }

    /** Nombre (virgule décimale admise) ; {@code null} si illisible. */
    public static BigDecimal nombre(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Date ISO {@code AAAA-MM-JJ} ; {@code null} si illisible ou inexistante (31 février). */
    public static LocalDate date(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
