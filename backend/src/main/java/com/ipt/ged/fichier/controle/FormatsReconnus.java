package com.ipt.ged.fichier.controle;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Correspondance entre les formats tels que l'administrateur les paramètre
 * sur un type documentaire ({@code pdf}, {@code docx}…) et les types réels
 * (MIME) que la détection par le contenu sait reconnaître.
 *
 * <p>La liste blanche reste exprimée en extensions côté écran — c'est ce que
 * l'administrateur connaît — mais la décision porte toujours sur le type réel.
 */
public final class FormatsReconnus {

    private FormatsReconnus() {}

    public static final String PDF = "application/pdf";
    public static final String TEXTE = "text/plain";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    public static final String ODT = "application/vnd.oasis.opendocument.text";
    public static final String ODS = "application/vnd.oasis.opendocument.spreadsheet";
    public static final String ODP = "application/vnd.oasis.opendocument.presentation";
    /** Conteneur OLE2 générique (doc, xls, ppt) : tika-core ne descend pas plus finement. */
    public static final String OLE2 = "application/x-tika-msoffice";

    /**
     * Liste blanche par défaut du dossier technique (§6.1.5) : PDF, TIFF, JPEG,
     * PNG, texte brut, CSV, DOCX, XLSX, PPTX, ODT, ODS, ODP.
     */
    public static final List<String> PAR_DEFAUT = List.of(
            "pdf", "tiff", "jpeg", "png", "txt", "csv", "docx", "xlsx", "pptx", "odt", "ods", "odp");

    private static final Map<String, Set<String>> PAR_EXTENSION = Map.ofEntries(
            Map.entry("pdf", Set.of(PDF)),
            Map.entry("tif", Set.of("image/tiff")),
            Map.entry("tiff", Set.of("image/tiff")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("png", Set.of("image/png")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("bmp", Set.of("image/bmp")),
            // Un CSV n'a pas de signature : par le contenu, c'est du texte brut.
            Map.entry("txt", Set.of(TEXTE)),
            Map.entry("csv", Set.of(TEXTE, "text/csv")),
            Map.entry("docx", Set.of(DOCX)),
            Map.entry("xlsx", Set.of(XLSX)),
            Map.entry("pptx", Set.of(PPTX)),
            Map.entry("odt", Set.of(ODT)),
            Map.entry("ods", Set.of(ODS)),
            Map.entry("odp", Set.of(ODP)),
            Map.entry("doc", Set.of("application/msword", OLE2)),
            Map.entry("xls", Set.of("application/vnd.ms-excel", OLE2)),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint", OLE2)),
            Map.entry("rtf", Set.of("application/rtf")));

    /**
     * Types réels admis pour une liste d'extensions paramétrées. Une extension
     * inconnue n'ouvre rien : elle ne peut pas élargir la liste blanche à un
     * type que la plateforme ne sait pas reconnaître.
     */
    public static Set<String> typesAdmis(Collection<String> extensions) {
        Set<String> types = new LinkedHashSet<>();
        for (String e : extensions) {
            Set<String> t = PAR_EXTENSION.get(e.trim().toLowerCase(Locale.ROOT));
            if (t != null) types.addAll(t);
        }
        return types;
    }

    public static boolean connue(String extension) {
        return extension != null && PAR_EXTENSION.containsKey(extension.trim().toLowerCase(Locale.ROOT));
    }

    /** Formats bureautiques : prévisualisés après conversion en PDF. */
    public static boolean bureautique(String typeMime) {
        return Set.of(DOCX, XLSX, PPTX, ODT, ODS, ODP, OLE2, "application/msword", "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint", "application/rtf").contains(typeMime);
    }

    /** Extension à donner au fichier temporaire soumis à LibreOffice, qui s'en sert pour choisir son filtre. */
    public static String extensionPour(String typeMime) {
        return switch (typeMime) {
            case DOCX -> "docx";
            case XLSX -> "xlsx";
            case PPTX -> "pptx";
            case ODT -> "odt";
            case ODS -> "ods";
            case ODP -> "odp";
            case "application/msword", OLE2 -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/rtf" -> "rtf";
            default -> "bin";
        };
    }
}
