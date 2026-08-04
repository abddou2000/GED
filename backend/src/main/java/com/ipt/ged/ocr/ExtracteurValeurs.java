package com.ipt.ged.ocr;

import com.ipt.ged.index.IndexField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Déduit une valeur d'index depuis le texte d'un document.
 *
 * <p>La stratégie est volontairement simple et explicable : on cherche d'abord
 * le <b>libellé de l'index</b> dans le texte et on lit ce qui le suit — c'est la
 * forme qu'ont les documents administratifs (« Fournisseur : ACME »). À défaut,
 * on se rabat sur un motif propre au type de l'index.
 *
 * <p>Aucune inférence statistique : ce qui n'est pas trouvé est laissé vide
 * plutôt que deviné, et tout passe de toute façon par la confirmation humaine.
 */
@Component
public class ExtracteurValeurs {

    /** « Libellé : valeur » ou « Libellé  valeur », jusqu'à la fin de la ligne. */
    private static final String APRES_LIBELLE = "\\s*[:=]?\\s*(.+)";

    private static final Pattern DATE_ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DATE_FR  = Pattern.compile("\\b(\\d{2})[/.](\\d{2})[/.](\\d{4})\\b");
    private static final Pattern NOMBRE   = Pattern.compile("\\b\\d+(?:[.,]\\d+)?\\b");

    /**
     * @return la valeur trouvée pour cet index, ou {@code null} si le texte ne permet pas de conclure.
     */
    public String deduire(IndexField champ, String texte, List<String> options) {
        if (texte == null || texte.isBlank()) return null;

        String parLibelle = chercherApresLibelle(champ.getNomIndex(), texte);

        return switch (champ.getFieldType()) {
            case LISTE -> options.stream()
                    .filter(o -> contientMot(texte, o))
                    .findFirst().orElse(null);

            case DATE -> {
                String candidat = parLibelle != null ? parLibelle : texte;
                String iso = premiereDate(candidat);
                yield iso != null ? iso : premiereDate(texte);
            }

            case NOMBRE -> {
                String candidat = parLibelle != null ? parLibelle : texte;
                Matcher m = NOMBRE.matcher(candidat);
                yield m.find() ? m.group().replace(',', '.') : null;
            }

            case TEXTE -> parLibelle != null && !parLibelle.isBlank() ? parLibelle : null;
        };
    }

    /** Texte suivant le libellé de l'index, sur la même ligne. */
    private String chercherApresLibelle(String libelle, String texte) {
        if (libelle == null || libelle.isBlank()) return null;
        Pattern p = Pattern.compile(Pattern.quote(libelle) + APRES_LIBELLE,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher m = p.matcher(texte);
        if (!m.find()) return null;
        // On s'arrête à la fin de ligne, et on coupe une éventuelle seconde étiquette
        String valeur = m.group(1).split("\\R")[0].strip();
        return valeur.isBlank() ? null : valeur;
    }

    /** Première date rencontrée, normalisée en AAAA-MM-JJ. */
    private String premiereDate(String texte) {
        Matcher iso = DATE_ISO.matcher(texte);
        if (iso.find()) return iso.group();
        Matcher fr = DATE_FR.matcher(texte);
        if (fr.find()) return fr.group(3) + "-" + fr.group(2) + "-" + fr.group(1);
        return null;
    }

    /** Présence du mot entier, insensible à la casse — évite que « Basse » morde sur « Basse-Normandie ». */
    private boolean contientMot(String texte, String mot) {
        if (mot == null || mot.isBlank()) return false;
        return Pattern.compile("\\b" + Pattern.quote(mot.strip()) + "\\b",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(texte)
                .find();
    }
}
