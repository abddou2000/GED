package com.ipt.ged.ocr.moteur;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Documents « scannés » générés pour les tests : images de texte, PDF image seule, PDF natif. */
public final class Scans {

    private Scans() {}

    /** Polices système capables de rendre l'arabe correctement (formes contextuelles). */
    private static final List<String> POLICES_ARABES = List.of("Tahoma", "Simplified Arabic", "Traditional Arabic",
            "Noto Naskh Arabic", "Noto Sans Arabic", "DejaVu Sans");

    public static Optional<String> policeArabe() {
        List<String> dispo = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        return POLICES_ARABES.stream().filter(dispo::contains)
                .filter(n -> new Font(n, Font.PLAIN, 12).canDisplayUpTo("عقد الإيجار") == -1)
                .findFirst();
    }

    /** Page A4 à 150 dpi portant des lignes de texte (arabe aligné à droite). */
    public static BufferedImage page(String police, List<String> lignes) {
        int l = 1240, h = 1754;
        BufferedImage img = new BufferedImage(l, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l, h);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(police, Font.PLAIN, 44));
        int y = 200;
        for (String ligne : lignes) {
            boolean arabe = ligne.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);
            int x = arabe ? l - 120 - g.getFontMetrics().stringWidth(ligne) : 120;
            g.drawString(ligne, x, y);
            y += 110;
        }
        g.dispose();
        return img;
    }

    public static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** PDF dont chaque page n'est qu'une image (scan) ou, si l'image est nulle, du texte natif. */
    public static byte[] pdf(List<Object> pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Object contenu : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    if (contenu instanceof BufferedImage img) {
                        PDImageXObject x = LosslessFactory.createFromImage(doc, img);
                        cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                    } else {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(60, 760);
                        cs.showText(String.valueOf(contenu));
                        cs.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
