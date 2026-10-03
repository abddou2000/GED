package com.ipt.ged.corpus;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polices du générateur : polices d'impression prises sur le système (Linux ou
 * Windows) et polices manuscrites embarquées sous licence OFL
 * ({@code src/test/resources/corpus/polices}).
 *
 * <p>Le rendu dépend des polices installées : deux postes différents produisent
 * des images légèrement différentes pour la même graine. Le texte de référence,
 * lui, est identique.
 */
final class Polices {

    private static final String[] IMPRIMEES = {"Liberation Serif", "Liberation Sans", "DejaVu Serif", "DejaVu Sans",
            "FreeSerif", "FreeSans", "Bitstream Charter", "Times New Roman", "Arial", "Calibri", "Cambria",
            "Georgia", "Verdana", "Tahoma"};
    private static final String[] MACHINE = {"Courier 10 Pitch", "Liberation Mono", "Courier New", "FreeMono"};
    private static final String[] MANUSCRITES = {"Caveat.ttf", "Kalam-Regular.ttf", "IndieFlower-Regular.ttf",
            "NothingYouCouldDo.ttf", "ReenieBeanie.ttf"};

    final List<Font> imprimees;
    final Font machine;
    final List<Font> manuscrites;

    private Polices(List<Font> imprimees, Font machine, List<Font> manuscrites) {
        this.imprimees = imprimees;
        this.machine = machine;
        this.manuscrites = manuscrites;
    }

    static Polices charger() {
        Set<String> dispo = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        List<Font> imp = new ArrayList<>();
        for (String n : IMPRIMEES) {
            if (dispo.contains(n)) {
                imp.add(new Font(n, Font.PLAIN, 10));
            }
        }
        if (imp.isEmpty()) {
            imp.add(new Font(Font.SERIF, Font.PLAIN, 10));
            imp.add(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        }
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 10);
        for (String n : MACHINE) {
            if (dispo.contains(n)) {
                mono = new Font(n, Font.PLAIN, 10);
                break;
            }
        }
        List<Font> man = new ArrayList<>();
        for (String f : MANUSCRITES) {
            try (InputStream in = Polices.class.getResourceAsStream("/corpus/polices/" + f)) {
                if (in != null) {
                    man.add(Font.createFont(Font.TRUETYPE_FONT, in));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Police manuscrite illisible : " + f, e);
            }
        }
        if (man.isEmpty()) {
            throw new IllegalStateException("Aucune police manuscrite trouvée dans /corpus/polices");
        }
        return new Polices(List.copyOf(imp), mono, List.copyOf(man));
    }
}
