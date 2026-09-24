package com.ipt.ged.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecture de la sortie TSV de Tesseract.
 *
 * <p>Le mode {@code tsv} rend une ligne par mot avec sa boîte englobante et son
 * indice de confiance. On y gagne deux choses par rapport au texte brut : la
 * position, qui permet de lire la valeur à côté de son étiquette, et la
 * confiance, qui permet d'écarter les mots que le moteur lui-même juge douteux.
 *
 * <p>Colonnes : level, page_num, block_num, par_num, line_num, word_num,
 * left, top, width, height, conf, text.
 */
final class LectureTsv {

    private LectureTsv() {}

    /** Niveau 5 = un mot ; les niveaux inférieurs décrivent page, bloc, ligne. */
    private static final int NIVEAU_MOT = 5;

    /** Sous ce seuil, le moteur avoue ne pas savoir : le mot est écarté. */
    private static final double CONFIANCE_MINIMALE = 40;

    static List<MotOcr> mots(String tsv, int pageDecalage) {
        List<MotOcr> mots = new ArrayList<>();
        if (tsv == null || tsv.isBlank()) return mots;

        String[] lignes = tsv.split("\\R");
        for (int i = 0; i < lignes.length; i++) {
            String[] c = lignes[i].split("\t", -1);
            if (c.length < 12) continue;
            if (i == 0 && "level".equals(c[0])) continue;   // en-tête

            try {
                if (Integer.parseInt(c[0]) != NIVEAU_MOT) continue;
                String texte = c[11].strip();
                if (texte.isEmpty()) continue;

                double conf = Double.parseDouble(c[10]);
                if (conf < CONFIANCE_MINIMALE) continue;

                mots.add(new MotOcr(texte,
                        Integer.parseInt(c[6]), Integer.parseInt(c[7]),
                        Integer.parseInt(c[8]), Integer.parseInt(c[9]),
                        conf,
                        pageDecalage,
                        Integer.parseInt(c[4])));
            } catch (NumberFormatException e) {
                // Ligne malformée : on la saute plutôt que d'interrompre la page
                // entière pour un mot.
            }
        }
        return mots;
    }

    /**
     * Reconstitue le texte lisible à partir des mots : un saut de ligne quand
     * l'indice de ligne change, un espace sinon. Sans cela il faudrait lancer
     * Tesseract une seconde fois rien que pour obtenir le texte à plat.
     */
    static String texte(List<MotOcr> mots) {
        StringBuilder sb = new StringBuilder();
        int pagePrecedente = -1;
        int lignePrecedente = -1;
        for (MotOcr m : mots) {
            if (m.page() != pagePrecedente) {
                if (sb.length() > 0) sb.append('\n');
                pagePrecedente = m.page();
                lignePrecedente = -1;
            }
            if (m.ligne() != lignePrecedente) {
                if (sb.length() > 0) sb.append('\n');
                lignePrecedente = m.ligne();
            } else {
                sb.append(' ');
            }
            sb.append(m.texte());
        }
        return sb.toString().strip();
    }
}
