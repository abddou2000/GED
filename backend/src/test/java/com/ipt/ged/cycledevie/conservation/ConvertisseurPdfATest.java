package com.ipt.ged.cycledevie.conservation;

import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.previsualisation.ConvertisseurLibreOffice;
import com.ipt.ged.fichier.previsualisation.FauxSoffice;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Copie de conservation PDF/A-2 (§6.1.4) : chaque résultat est validé par le
 * vrai veraPDF. LibreOffice est simulé (FauxSoffice), la validation ne l'est pas.
 */
class ConvertisseurPdfATest {

    private final ValidateurPdfA verapdf = new ValidateurVeraPdf();

    @TempDir
    Path dossier;

    private ConvertisseurPdfA convertisseur(List<String> soffice) {
        return new ConvertisseurPdfA(new ConvertisseurLibreOffice(soffice, Duration.ofSeconds(60)), verapdf);
    }

    private ConvertisseurPdfA sansLibreOffice() {
        return convertisseur(List.of(dossier.resolve("soffice-absent").toString()));
    }

    private Path fichier(String nom, byte[] contenu) throws IOException {
        return Files.write(dossier.resolve(nom), contenu);
    }

    private Path travail() throws IOException {
        return Files.createTempDirectory(dossier, "travail-");
    }

    private void assertPdfA(ConvertisseurPdfA.Copie c) {
        ValidateurPdfA.Validation v = verapdf.valider(c.pdf());
        assertTrue(v.conforme(), v.detail());
    }

    @Test
    @DisplayName("PNG avec transparence, JPEG : une page PDF/A-2B conforme (PDFBox)")
    void images() throws Exception {
        BufferedImage alpha = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        alpha.setRGB(5, 5, 0x80FF0000);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(alpha, "png", png);
        ConvertisseurPdfA.Copie c = sansLibreOffice().convertir(fichier("a.png", png.toByteArray()), "image/png",
                "Plan", travail());
        assertEquals("PDFBOX_IMAGE", c.methode());
        assertPdfA(c);

        ConvertisseurPdfA.Copie j = sansLibreOffice().convertir(fichier("b.jpg", Echantillons.image("jpeg")),
                "image/jpeg", "Photo", travail());
        assertEquals("PDFBOX_IMAGE", j.methode());
        assertPdfA(j);
    }

    @Test
    @DisplayName("TIFF de trois pages : trois pages PDF/A")
    void tiffMultipage() throws Exception {
        Path tiff = dossier.resolve("scan.tif");
        ImageWriter w = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(tiff.toFile())) {
            w.setOutput(out);
            w.prepareWriteSequence(null);
            for (int i = 0; i < 3; i++) {
                w.writeToSequence(new IIOImage(new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY), null, null), null);
            }
            w.endWriteSequence();
        } finally {
            w.dispose();
        }
        ConvertisseurPdfA.Copie c = sansLibreOffice().convertir(tiff, "image/tiff", "Scan", travail());
        assertPdfA(c);
        try (PDDocument d = Loader.loadPDF(c.pdf().toFile())) {
            assertEquals(3, d.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("PDF sans police : identification PDF/A ajoutée, contenu conservé (PDFBOX_PDF), puis déjà conforme")
    void pdfNormalise() throws Exception {
        ConvertisseurPdfA.Copie c = sansLibreOffice().convertir(fichier("simple.pdf", Echantillons.pdf()),
                "application/pdf", "Simple", travail());
        assertEquals("PDFBOX_PDF", c.methode());
        assertPdfA(c);

        // Une copie déjà PDF/A-2 est gardée telle quelle.
        Path copie = fichier("deja.pdf", Files.readAllBytes(c.pdf()));
        ConvertisseurPdfA.Copie d = sansLibreOffice().convertir(copie, "application/pdf", "Simple", travail());
        assertEquals("PDF_CONFORME", d.methode());
        assertEquals(copie, d.pdf());
    }

    @Test
    @DisplayName("PDF à police non incorporée : repli par rendu en images (RASTERISATION), conforme")
    void pdfRasterise() throws Exception {
        Path pdf = dossier.resolve("texte.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream flux = new PDPageContentStream(doc, page)) {
                flux.beginText();
                flux.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                flux.newLineAtOffset(50, 700);
                flux.showText("Facture 2026");
                flux.endText();
            }
            doc.save(pdf.toFile());
        }
        ConvertisseurPdfA.Copie c = sansLibreOffice().convertir(pdf, "application/pdf", "Facture", travail());
        assertEquals("RASTERISATION", c.methode());
        assertPdfA(c);
    }

    @Test
    @DisplayName("Word par LibreOffice (simulé) avec export PDF/A-2 : copie validée par veraPDF")
    void bureautique() throws Exception {
        ConvertisseurPdfA.Copie c = convertisseur(FauxSoffice.commande()).convertir(
                fichier("contrat.docx", Echantillons.docx()),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Contrat", travail());
        assertEquals("LIBREOFFICE", c.methode());
        assertPdfA(c);
    }

    @Test
    @DisplayName("Échecs motivés : LibreOffice absent, format sans conversion, PDF illisible")
    void echecs() throws Exception {
        ConvertisseurPdfA.ConversionImpossible e = assertThrows(ConvertisseurPdfA.ConversionImpossible.class,
                () -> sansLibreOffice().convertir(fichier("c.docx", Echantillons.docx()),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "C", travail()));
        assertTrue(e.getMessage().contains("LibreOffice"), e.getMessage());

        e = assertThrows(ConvertisseurPdfA.ConversionImpossible.class,
                () -> sansLibreOffice().convertir(fichier("x.bin", new byte[]{1, 2}), "application/zip", "X", travail()));
        assertTrue(e.getMessage().contains("application/zip"), e.getMessage());

        e = assertThrows(ConvertisseurPdfA.ConversionImpossible.class,
                () -> sansLibreOffice().convertir(fichier("faux.pdf", "%PDF-1.4 pas un pdf".getBytes()),
                        "application/pdf", "F", travail()));
        assertTrue(e.getMessage().contains("rendu en images"), e.getMessage());
    }
}
