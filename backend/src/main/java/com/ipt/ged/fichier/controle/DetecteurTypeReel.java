package com.ipt.ged.fichier.controle;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Détermine le type réel d'un fichier par son contenu, avec Apache Tika
 * (§6.1.5).
 *
 * <p>Le nom du fichier n'est <b>pas</b> transmis à Tika : avec lui, Tika
 * départage les cas ambigus par l'extension, et un exécutable renommé en
 * {@code .pdf} passerait pour ce qu'il prétend être.
 *
 * <p>Le noyau de Tika reconnaît un paquet OOXML sans savoir s'il s'agit d'un
 * texte, d'un classeur ou d'une présentation (cette finesse est dans le module
 * d'analyse Microsoft, qui embarque une seconde copie de POI). La distinction
 * est faite ici en lisant {@code [Content_Types].xml}, la déclaration normative
 * du paquet (ECMA-376 partie 2) : c'est aussi elle qui révèle les variantes à
 * macros ({@code docm}, {@code xlsm}), refusées par défaut.
 */
public class DetecteurTypeReel {

    private static final int ENTREES_ZIP_MAX = 64;
    private static final int CONTENT_TYPES_MAX = 512 * 1024;

    /** Type de la partie principale déclarée → type du document. L'ordre compte : macros d'abord. */
    private static final Map<String, String> PARTIES_PRINCIPALES = new java.util.LinkedHashMap<>();
    static {
        PARTIES_PRINCIPALES.put("application/vnd.ms-word.document.macroenabled.main+xml",
                "application/vnd.ms-word.document.macroEnabled.12");
        PARTIES_PRINCIPALES.put("application/vnd.ms-excel.sheet.macroenabled.main+xml",
                "application/vnd.ms-excel.sheet.macroEnabled.12");
        PARTIES_PRINCIPALES.put("application/vnd.ms-powerpoint.slideshow.macroenabled.main+xml",
                "application/vnd.ms-powerpoint.slideshow.macroEnabled.12");
        PARTIES_PRINCIPALES.put("application/vnd.ms-powerpoint.presentation.macroenabled.main+xml",
                "application/vnd.ms-powerpoint.presentation.macroEnabled.12");
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.template");
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template");
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
                "application/vnd.openxmlformats-officedocument.presentationml.template");
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
                "application/vnd.openxmlformats-officedocument.presentationml.slideshow");
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                FormatsReconnus.DOCX);
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                FormatsReconnus.XLSX);
        PARTIES_PRINCIPALES.put("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                FormatsReconnus.PPTX);
    }

    private final Detector detecteur = TikaConfig.getDefaultConfig().getDetector();

    /** @return type MIME sans paramètres (ex. {@code application/pdf}). */
    public String detecter(SourceFichier source) {
        MediaType type;
        try (InputStream in = new BufferedInputStream(source.ouvrir())) {
            type = detecteur.detect(in, new Metadata());
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier pour la détection de type impossible", e);
        }
        String base = type.getBaseType().toString();
        if ("application/zip".equals(base) || "application/x-tika-ooxml".equals(base)) {
            return affinerOoxml(source, base);
        }
        return base;
    }

    private static String affinerOoxml(SourceFichier source, String parDefaut) {
        try (ZipInputStream zip = new ZipInputStream(source.ouvrir())) {
            ZipEntry entree;
            int vues = 0;
            while ((entree = zip.getNextEntry()) != null && vues++ < ENTREES_ZIP_MAX) {
                if ("[Content_Types].xml".equals(entree.getName())) {
                    String declaration = new String(zip.readNBytes(CONTENT_TYPES_MAX), StandardCharsets.UTF_8)
                            .toLowerCase(java.util.Locale.ROOT);
                    for (Map.Entry<String, String> e : PARTIES_PRINCIPALES.entrySet()) {
                        if (declaration.contains("\"" + e.getKey() + "\"")) return e.getValue();
                    }
                    return parDefaut;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // Archive malformée : elle n'est pas un document bureautique valide.
            return parDefaut;
        }
        return parDefaut;
    }
}
