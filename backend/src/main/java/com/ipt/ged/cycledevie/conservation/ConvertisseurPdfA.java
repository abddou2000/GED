package com.ipt.ged.cycledevie.conservation;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.previsualisation.ConvertisseurBureautique;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copie de conservation PDF/A-2 d'un fichier (§6.1.4) : LibreOffice sans
 * interface pour les formats bureautiques (Word compris, revue client D10),
 * PDFBox pour les images et les PDF ; chaque candidat est <b>validé par
 * veraPDF</b> et seul un fichier conforme est retenu.
 *
 * <p>Enchaînement, du plus fidèle au moins fidèle :
 * <ol>
 *   <li>PDF déjà PDF/A-2 conforme : conservé tel quel ({@code PDF_CONFORME}) ;</li>
 *   <li>bureautique : export PDF/A-2 de LibreOffice ({@code LIBREOFFICE}) ;
 *       image : pages d'images ({@code PDFBOX_IMAGE}) ;</li>
 *   <li>PDF (d'origine ou issu de LibreOffice) : identification PDF/A ajoutée
 *       par PDFBox, contenu conservé, couche texte comprise
 *       ({@code PDFBOX_PDF}) ;</li>
 *   <li>repli : rendu des pages en images ({@code RASTERISATION}) — le PDF est
 *       alors « essentiellement une image » (question Q7).</li>
 * </ol>
 * Si aucun candidat n'est conforme : {@link ConversionImpossible}, avec le
 * motif de chaque tentative ; l'archivage n'est pas bloqué pour autant.
 */
public class ConvertisseurPdfA {

    /** Filtre d'export LibreOffice par famille de document. */
    private static final Map<String, String> FILTRES = Map.ofEntries(
            Map.entry("application/msword", "writer_pdf_Export"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "writer_pdf_Export"),
            Map.entry("application/vnd.oasis.opendocument.text", "writer_pdf_Export"),
            Map.entry("application/rtf", "writer_pdf_Export"),
            Map.entry("text/plain", "writer_pdf_Export"),
            Map.entry("application/vnd.ms-excel", "calc_pdf_Export"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "calc_pdf_Export"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "calc_pdf_Export"),
            Map.entry("text/csv", "calc_pdf_Export"),
            Map.entry("application/vnd.ms-powerpoint", "impress_pdf_Export"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "impress_pdf_Export"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "impress_pdf_Export"));

    private final ConvertisseurBureautique libreOffice;
    private final ValidateurPdfA validateur;

    public ConvertisseurPdfA(ConvertisseurBureautique libreOffice, ValidateurPdfA validateur) {
        this.libreOffice = libreOffice;
        this.validateur = validateur;
    }

    /** Copie produite : fichier PDF/A-2 validé et chaîne qui l'a produit. */
    public record Copie(Path pdf, String methode) {
    }

    /** Aucune copie conforme n'a pu être produite ; le message cumule les motifs. */
    public static class ConversionImpossible extends Exception {
        public ConversionImpossible(String motif) {
            super(motif);
        }
    }

    /** Cible LibreOffice : export PDF avec « SelectPdfVersion = 2 » (PDF/A-2b). */
    static String cibleLibreOffice(String filtre) {
        return "pdf:" + filtre + ":{\"SelectPdfVersion\":{\"type\":\"long\",\"value\":\"2\"}}";
    }

    /**
     * @param source   fichier en clair (répertoire de travail éphémère) ;
     * @param typeMime type réel détecté au dépôt ;
     * @param titre    titre inscrit dans les métadonnées XMP ;
     * @param travail  répertoire de travail, vidé par l'appelant.
     */
    public Copie convertir(Path source, String typeMime, String titre, Path travail) throws ConversionImpossible {
        List<String> motifs = new ArrayList<>();
        String mime = typeMime != null ? typeMime.toLowerCase() : "";
        if (mime.equals("application/pdf")) {
            ValidateurPdfA.Validation v = validateur.valider(source);
            if (v.conforme()) return new Copie(source, "PDF_CONFORME");
            motifs.add("original : " + v.detail());
            return depuisPdf(source, titre, travail, motifs, "PDFBOX_PDF");
        }
        if (mime.startsWith("image/")) {
            Path pdf = travail.resolve("image-pdfa.pdf");
            try {
                FabriquePdfA.depuisImages(source, mime, titre, pdf);
                ValidateurPdfA.Validation v = validateur.valider(pdf);
                if (v.conforme()) return new Copie(pdf, "PDFBOX_IMAGE");
                motifs.add("image : " + v.detail());
            } catch (IOException | RuntimeException e) {
                motifs.add("image : " + message(e));
            }
            throw new ConversionImpossible(String.join(" | ", motifs));
        }
        String filtre = FILTRES.get(mime);
        if (filtre == null) {
            throw new ConversionImpossible("format " + typeMime + " sans conversion PDF/A prévue");
        }
        if (!libreOffice.disponible()) {
            throw new ConversionImpossible("LibreOffice n'est pas installé sur le serveur");
        }
        Path pdf;
        try {
            Path sortie = Files.createDirectories(travail.resolve("libreoffice"));
            pdf = libreOffice.convertir(source, sortie, cibleLibreOffice(filtre));
        } catch (ErreurFichierException | IOException e) {
            throw new ConversionImpossible("LibreOffice : " + message(e));
        }
        ValidateurPdfA.Validation v = validateur.valider(pdf);
        if (v.conforme()) return new Copie(pdf, "LIBREOFFICE");
        motifs.add("LibreOffice : " + v.detail());
        return depuisPdf(pdf, titre, travail, motifs, "LIBREOFFICE_PDFBOX");
    }

    private Copie depuisPdf(Path pdf, String titre, Path travail, List<String> motifs, String methodeNormalisation)
            throws ConversionImpossible {
        Path normalise = travail.resolve("normalise-pdfa.pdf");
        try {
            FabriquePdfA.normaliser(pdf, titre, normalise);
            ValidateurPdfA.Validation v = validateur.valider(normalise);
            if (v.conforme()) return new Copie(normalise, methodeNormalisation);
            motifs.add("mise en conformité : " + v.detail());
        } catch (IOException | RuntimeException e) {
            motifs.add("mise en conformité : " + message(e));
        }
        Path image = travail.resolve("rendu-pdfa.pdf");
        try {
            FabriquePdfA.rasteriser(pdf, titre, image);
            ValidateurPdfA.Validation v = validateur.valider(image);
            if (v.conforme()) return new Copie(image, "RASTERISATION");
            motifs.add("rendu en images : " + v.detail());
        } catch (IOException | RuntimeException e) {
            motifs.add("rendu en images : " + message(e));
        }
        throw new ConversionImpossible(String.join(" | ", motifs));
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
