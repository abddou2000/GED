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
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

/**
 * Lecture du texte natif des fichiers bureautiques Word et Excel, pour la
 * chaîne OCR asynchrone (lot E6) : le texte y est natif, donc fiable, et ne
 * passe pas par la reconnaissance optique.
 */
@Component
public class ExtracteurBureautique {

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

}
