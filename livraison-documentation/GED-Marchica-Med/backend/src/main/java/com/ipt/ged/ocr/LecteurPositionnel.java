package com.ipt.ged.ocr;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Retrouve la valeur d'un champ à partir de la position des mots sur la page.
 *
 * <p>Dans un texte à plat, « Fournisseur » suivi de « ACME » sur la ligne
 * d'après et « Fournisseur ACME » sur la même ligne se ressemblent — alors que
 * dans un formulaire ce sont deux mises en page différentes, et dans un tableau
 * l'étiquette peut même être en en-tête de colonne. Les coordonnées permettent
 * de trancher : on regarde d'abord à droite de l'étiquette, puis en dessous.
 *
 * <p>Rien n'est deviné : si aucune zone plausible ne contient de texte, la
 * méthode renvoie {@code null} et la saisie reste à l'opérateur.
 */
@Component
public class LecteurPositionnel {

    /** Deux mots sont sur la même bande si leurs milieux verticaux sont proches. */
    private static final double TOLERANCE_LIGNE = 0.6;

    /** Au-delà, le mot « à droite » appartient visiblement à une autre colonne. */
    private static final int ECART_HORIZONTAL_MAX = 600;

    /**
     * @param libelle étiquette recherchée, telle que nommée dans l'index
     * @return le texte lu à côté de l'étiquette, ou {@code null}
     */
    public String valeurPour(String libelle, List<MotOcr> mots) {
        if (libelle == null || libelle.isBlank() || mots == null || mots.isEmpty()) return null;

        List<MotOcr> etiquette = localiser(libelle, mots);
        if (etiquette.isEmpty()) return null;

        MotOcr dernier = etiquette.get(etiquette.size() - 1);
        MotOcr premier = etiquette.get(0);

        String aDroite = lireADroite(dernier, mots);
        if (aDroite != null) return aDroite;

        return lireEnDessous(premier, dernier, mots);
    }

    /** Mots de la page formant l'étiquette, dans l'ordre de lecture. */
    private List<MotOcr> localiser(String libelle, List<MotOcr> mots) {
        String[] attendus = normaliser(libelle).split("\\s+");
        if (attendus.length == 0) return List.of();

        for (int i = 0; i + attendus.length <= mots.size(); i++) {
            boolean concordance = true;
            for (int j = 0; j < attendus.length; j++) {
                MotOcr m = mots.get(i + j);
                // Les mots de l'étiquette doivent rester sur la même ligne :
                // sinon « Date » d'un bloc et « émission » d'un autre suffiraient
                // à déclencher une fausse correspondance.
                if (m.page() != mots.get(i).page() || m.ligne() != mots.get(i).ligne()
                        || !nettoyer(normaliser(m.texte())).equals(nettoyer(attendus[j]))) {
                    concordance = false;
                    break;
                }
            }
            if (concordance) return mots.subList(i, i + attendus.length);
        }
        return List.of();
    }

    /** Texte situé sur la même bande, après l'étiquette. */
    private String lireADroite(MotOcr fin, List<MotOcr> mots) {
        double tolerance = fin.hauteur() * TOLERANCE_LIGNE;
        List<MotOcr> suite = mots.stream()
                .filter(m -> m.page() == fin.page())
                .filter(m -> m.x() >= fin.droite())
                .filter(m -> m.x() - fin.droite() <= ECART_HORIZONTAL_MAX)
                .filter(m -> Math.abs(m.centreY() - fin.centreY()) <= tolerance)
                .sorted(Comparator.comparingInt(MotOcr::x))
                .toList();
        return assembler(suite);
    }

    /**
     * Texte de la ligne suivante, aligné sous l'étiquette — disposition
     * courante des formulaires et des en-têtes de colonne.
     */
    private String lireEnDessous(MotOcr debut, MotOcr fin, List<MotOcr> mots) {
        int basEtiquette = fin.y() + fin.hauteur();
        double hauteurMax = fin.hauteur() * 2.5;

        List<MotOcr> dessous = mots.stream()
                .filter(m -> m.page() == fin.page())
                .filter(m -> m.y() > basEtiquette && m.y() - basEtiquette <= hauteurMax)
                .filter(m -> m.droite() >= debut.x() && m.x() <= fin.droite() + ECART_HORIZONTAL_MAX)
                .sorted(Comparator.comparingInt(MotOcr::x))
                .toList();
        if (dessous.isEmpty()) return null;

        // On ne garde que la première ligne rencontrée sous l'étiquette.
        int ligne = dessous.get(0).ligne();
        List<MotOcr> premiere = dessous.stream().filter(m -> m.ligne() == ligne).toList();
        return assembler(premiere);
    }

    /** Recolle les mots en écartant les séparateurs de tête (« : », « = »). */
    private String assembler(List<MotOcr> mots) {
        List<String> utiles = new ArrayList<>();
        for (MotOcr m : mots) {
            String s = m.texte().strip();
            if (utiles.isEmpty() && (s.equals(":") || s.equals("=") || s.equals("-"))) continue;
            if (utiles.isEmpty()) s = s.replaceFirst("^[:=\\-]\\s*", "");
            if (!s.isBlank()) utiles.add(s);
        }
        String valeur = String.join(" ", utiles).strip();
        return valeur.isBlank() ? null : valeur;
    }

    /** Minuscules sans accents : l'OCR rend « Numéro » ou « Numero » selon le scan. */
    private static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    /** Retire la ponctuation de bord, que le moteur colle parfois au mot. */
    private static String nettoyer(String s) {
        return s.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
    }
}
