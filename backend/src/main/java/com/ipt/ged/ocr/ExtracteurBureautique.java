package com.ipt.ged.ocr;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Lecture des fichiers bureautiques : Word et Excel.
 *
 * <p>Les types de document autorisent {@code docx, doc, xlsx, xls}, mais seuls
 * le PDF et les images étaient lisibles : un contrat Word déposé ne pouvait pas
 * être indexé automatiquement, sans que rien ne l'explique à l'opérateur.
 *
 * <p>Le texte y est natif, donc fiable : cet étage passe avant l'OCR.
 */
@Component
public class ExtracteurBureautique implements ExtracteurTexte {

    private static final Logger log = LoggerFactory.getLogger(ExtracteurBureautique.class);

    private static final Set<String> FORMATS = Set.of("docx", "doc", "xlsx", "xls");

    @Override public String nom() { return "Bureautique (Apache POI)"; }
    @Override public boolean disponible() { return true; }
    @Override public int priorite() { return 15; }

    @Override
    public boolean gere(String extension) {
        return extension != null && FORMATS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public TexteExtrait extraire(Path fichier) {
        String ext = extension(fichier).toLowerCase(Locale.ROOT);
        try (InputStream in = Files.newInputStream(fichier)) {
            String texte = lireFlux(in, ext);
            return texte.isBlank()
                    ? TexteExtrait.aucune("Le fichier ne contient aucun texte lisible.")
                    : new TexteExtrait(texte.strip(), TexteExtrait.Provenance.COUCHE_TEXTE, 1,
                            "Texte natif du fichier " + ext + ".");
        } catch (Exception e) {
            log.warn("Lecture bureautique impossible : {}", fichier, e);
            return TexteExtrait.aucune("Lecture impossible : " + e.getMessage());
        }
    }

    /**
     * Texte natif lu depuis un flux : la chaîne OCR asynchrone (lot E6) lit
     * les fichiers déchiffrés en mémoire, sans jamais les poser en clair sur
     * un disque.
     *
     * @return texte brut, vide pour une extension non gérée.
     */
    public String lireFlux(InputStream in, String extension) throws Exception {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "docx" -> lireDocx(in);
            case "doc"  -> lireDoc(in);
            case "xlsx" -> lireClasseur(new XSSFWorkbook(in));
            case "xls"  -> lireClasseur(new HSSFWorkbook(in));
            default -> "";
        };
    }

    private String lireDocx(InputStream in) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private String lireDoc(InputStream in) throws Exception {
        try (WordExtractor ex = new WordExtractor(in)) {
            return ex.getText();
        }
    }

    /**
     * Une cellule par mot ne suffirait pas : on garde la structure en lignes,
     * séparateur tabulation, pour que « Fournisseur | ACME » reste lisible comme
     * une étiquette suivie de sa valeur.
     */
    private String lireClasseur(Workbook classeur) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter format = new DataFormatter(Locale.FRANCE);
        try (classeur) {
            for (Sheet feuille : classeur) {
                for (Row ligne : feuille) {
                    StringBuilder l = new StringBuilder();
                    for (Cell cellule : ligne) {
                        String v = format.formatCellValue(cellule).strip();
                        if (!v.isEmpty()) {
                            if (l.length() > 0) l.append('\t');
                            l.append(v);
                        }
                    }
                    if (l.length() > 0) sb.append(l).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String extension(Path fichier) {
        String n = fichier.getFileName().toString();
        int point = n.lastIndexOf('.');
        return point > 0 ? n.substring(point + 1) : "";
    }
}
