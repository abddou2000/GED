package com.ipt.ged.fichier.controle;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Fichiers d'exemple réels, produits par les bibliothèques des formats eux-mêmes. */
public final class Echantillons {

    private Echantillons() {}

    public static byte[] pdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, out)) throw new IllegalStateException("format " + format);
        return out.toByteArray();
    }

    public static byte[] docx() throws IOException {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.createParagraph().createRun().setText("Contrat de prestation");
            d.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] xlsx() throws IOException {
        try (XSSFWorkbook w = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            w.createSheet("Budget").createRow(0).createCell(0).setCellValue(42);
            w.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] pptx() throws IOException {
        try (XMLSlideShow s = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            s.createSlide();
            s.write(out);
            return out.toByteArray();
        }
    }

    /** Document Word à macros : un docx dont le paquet déclare une partie principale « macroEnabled ». */
    public static byte[] docm() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx()));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] contenu = in.readAllBytes();
                if (e.getName().equals("[Content_Types].xml")) {
                    contenu = new String(contenu, StandardCharsets.UTF_8)
                            .replace("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                                    "application/vnd.ms-word.document.macroEnabled.main+xml")
                            .getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write(contenu);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Paquet OpenDocument minimal : entrée « mimetype » en tête, non compressée (norme ODF). */
    public static byte[] odf(String typeMime) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] mimetype = typeMime.getBytes(StandardCharsets.US_ASCII);
            ZipEntry m = new ZipEntry("mimetype");
            m.setMethod(ZipEntry.STORED);
            m.setSize(mimetype.length);
            m.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            m.setCrc(crc.getValue());
            zip.putNextEntry(m);
            zip.write(mimetype);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write("<?xml version=\"1.0\"?><office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    public static byte[] zipQuelconque() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("lisezmoi.txt"));
            zip.write("archive".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** En-tête d'exécutable Windows (MZ … PE). */
    public static byte[] executable() {
        byte[] b = new byte[512];
        b[0] = 'M';
        b[1] = 'Z';
        b[0x3C] = (byte) 0x80;
        b[0x80] = 'P';
        b[0x81] = 'E';
        return b;
    }
}
