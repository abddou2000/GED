import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Recette E10 — P-14 (tour 7) : PDF « scanné » d'une page (image JPEG en niveaux de gris à
 * 300 dpi, AUCUNE couche texte), portant un mot témoin français et un mot témoin arabe
 * inédits, pour vérifier de bout en bout qu'un dépôt passe en OCR et devient recherchable.
 * PDFBox du classpath du back-end (aucune dépendance de plus, D5).
 *
 * <p>Usage : {@code java -cp <classpath backend> ScanTemoin.java <sortie.pdf> <témoin fr>}
 */
public class ScanTemoin {
    public static void main(String[] a) throws Exception {
        String sortie = a[0], fr = a[1], ar = "قرطاسيون"; // témoin arabe écrit ici : l'argument passerait par l'encodage du système
        int dpi = 300, w = 2480, h = 3508; // A4 à 300 dpi
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(25, 25, 25));
        Font serif = new Font(Font.SERIF, Font.PLAIN, 12 * dpi / 72); // corps 12 pt
        g.setFont(serif.deriveFont(Font.BOLD, 16f * dpi / 72));
        g.drawString("Agence Marchica Med - Direction administrative", 200, 300);
        g.setFont(serif);
        String[] lignes = {
                "Objet : transmission du dossier de recette numero " + fr + ".",
                "Monsieur le Directeur, nous avons l'honneur de vous adresser le present courrier",
                "relatif a la convention de partenariat signee le douze mars, ainsi que les pieces",
                "justificatives demandees lors de la reunion du comite de suivi. Le dossier " + fr,
                "comprend la facture, le bon de livraison et le proces-verbal de reception.",
                "Nous restons a votre disposition pour tout complement d'information.",
                "Veuillez agreer, Monsieur le Directeur, l'expression de nos salutations distinguees.",
        };
        int y = 520;
        for (String l : lignes) {
            g.drawString(l, 200, y);
            y += 80;
        }
        {
            g.setFont(new Font("DejaVu Sans", Font.PLAIN, 13 * dpi / 72));
            String l = "الموضوع : ملف الاستلام رقم " + ar + " المتعلق بالاتفاقية";
            int lx = w - 200 - g.getFontMetrics().stringWidth(l);
            g.drawString(l, lx, y + 80);
        }
        g.dispose();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject x = JPEGFactory.createFromImage(doc, img, 0.85f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(new File(sortie));
        }
    }
}
