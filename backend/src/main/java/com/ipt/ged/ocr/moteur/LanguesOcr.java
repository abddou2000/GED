package com.ipt.ged.ocr.moteur;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Langue de reconnaissance d'un type de document (§4.3.4) : {@code fra+ara}
 * par défaut (documents bilingues ou mixtes), réglable par type documentaire.
 *
 * <p>Le réglage est lu dans la configuration ({@code ged.ocr.langues.par-type},
 * indexé par <b>code</b> du type) plutôt que dans une colonne : le modèle des
 * types est en cours de migration (lot dev1). Au branchement, une colonne
 * {@code type_document.langue_ocr} pourra remplacer ce tableau sans changer
 * l'appelant. Seules les langues du dossier sont admises, pour qu'un réglage
 * erroné soit refusé au démarrage et non à 3 h du matin par le worker.
 */
public class LanguesOcr {

    /** Modèles retenus par le dossier technique : {@code fra} et {@code ara}. */
    public static final Set<String> ADMISES = Set.of("fra", "ara");
    private static final Pattern FORME = Pattern.compile("[a-z]{3}(\\+[a-z]{3})*");

    private final String defaut;
    private final Map<String, String> parType;

    public LanguesOcr(String defaut, Map<String, String> parType) {
        this.defaut = valider(defaut, "ged.ocr.langues.defaut");
        TreeMap<String, String> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (parType != null) {
            parType.forEach((type, langue) -> m.put(type, valider(langue, "ged.ocr.langues.par-type." + type)));
        }
        this.parType = m;
    }

    public String defaut() {
        return defaut;
    }

    /** Langue du type documentaire, ou la langue par défaut. */
    public String pour(String codeTypeDocument) {
        if (codeTypeDocument == null) return defaut;
        return parType.getOrDefault(codeTypeDocument, defaut);
    }

    private static String valider(String langue, String cle) {
        String l = langue == null ? "" : langue.trim().toLowerCase(Locale.ROOT);
        if (!FORME.matcher(l).matches()) {
            throw new IllegalStateException(cle + " : langue OCR invalide « " + langue + " » (ex. fra+ara)");
        }
        for (String m : l.split("\\+")) {
            if (!ADMISES.contains(m)) {
                throw new IllegalStateException(cle + " : modèle « " + m + " » non retenu (admis : fra, ara)");
            }
        }
        return l;
    }
}
