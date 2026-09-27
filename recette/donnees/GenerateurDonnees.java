import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Génère les jeux de données de recette de la GED Marchica Med.
 *
 * <p>Pourquoi un générateur plutôt que des fichiers « trouvés » : chaque fichier porte un
 * mot-témoin connu (preuve qu'il est retrouvé par son contenu en E6), son type réel est
 * maîtrisé (contrôles Tika en E5) et il ne contient aucune donnée réelle de MMED.
 *
 * <p>Java seul (décision D5 de la revue technique : aucun Python). Le rendu des scans passe
 * par Java2D, qui met en forme l'arabe (ligatures, formes contextuelles, sens RTL)
 * nativement ; les PDF sont écrits avec PDFBox, déjà embarqué par l'application.
 *
 * <p>Usage (classpath d'exécution du backend, voir generer-donnees.sh) :
 * <pre>
 *   java -Dfile.encoding=UTF-8 -cp CP GenerateurDonnees.java [--sortie DOSSIER] [--pages-scan N] [--police-arabe F.ttf]
 * </pre>
 */
public class GenerateurDonnees {

    static final String TEMOIN_FR = "zarkolinet";
    static final String TEMOIN_AR = "زركولين";
    static final int DPI_SCAN = 300;
    static final Random ALEA = new Random(20260926L); // dégradations reproductibles

    static final List<String> COURRIER_FR = List.of(
            "Agence pour l'Aménagement du Site de la Lagune de Marchica",
            "Direction Administrative et Financière — Bureau d'ordre",
            "Nador, le 26 septembre 2026",
            "Objet : convention de partenariat relative à l'aménagement des berges de la lagune",
            "Référence de recette : " + TEMOIN_FR,
            "Monsieur le Directeur,",
            "Nous avons l'honneur de vous transmettre, pour signature, la convention de partenariat portant sur la "
                    + "réhabilitation écologique des berges et la création d'un sentier pédestre de sept kilomètres. Le "
                    + "montant prévisionnel des travaux s'élève à 4 250 000,00 dirhams toutes taxes comprises, réparti sur "
                    + "les exercices 2026 et 2027.",
            "Les pièces jointes comprennent le décompte provisoire numéro 14, le procès-verbal de la réunion de "
                    + "coordination du 12 septembre 2026 et le planning d'exécution révisé.",
            "Veuillez agréer, Monsieur le Directeur, l'expression de notre haute considération.");

    static final List<String> COURRIER_AR = List.of(
            "المملكة المغربية",
            "وكالة تهيئة موقع بحيرة مارتشيكا",
            "مكتب الضبط — المديرية الإدارية والمالية",
            "الناظور في 26 شتنبر 2026",
            "الموضوع : اتفاقية شراكة تتعلق بتهيئة ضفاف البحيرة",
            "مرجع الاختبار : " + TEMOIN_AR,
            "السيد المدير المحترم،",
            "يشرفنا أن نوافيكم قصد التوقيع باتفاقية الشراكة المتعلقة بإعادة التأهيل البيئي لضفاف البحيرة وإحداث ممر "
                    + "للراجلين على طول سبعة كيلومترات. ويبلغ المبلغ التقديري للأشغال أربعة ملايين ومائتين وخمسين ألف درهم "
                    + "مع احتساب جميع الرسوم.",
            "وتتضمن الوثائق المرفقة الكشف المؤقت رقم 14 ومحضر اجتماع التنسيق المنعقد بتاريخ 12 شتنبر 2026 والجدول "
                    + "الزمني المعدل للإنجاز.",
            "وتقبلوا، السيد المدير، فائق عبارات التقدير والاحترام.");

    static final String[] POLICES_ARABES = {
            "C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/trado.ttf",
            "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"};

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path sortie = Path.of(".");
        int pagesScan = 2;
        String policeImposee = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sortie" -> sortie = Path.of(args[++i]);
                case "--pages-scan" -> pagesScan = Integer.parseInt(args[++i]);
                case "--police-arabe" -> policeImposee = args[++i];
                default -> throw new IllegalArgumentException("option inconnue : " + args[i]);
            }
        }
        Files.createDirectories(sortie);
        File police = trouverPolice(policeImposee);
        Font baseArabe = Font.createFont(Font.TRUETYPE_FONT, police);

        if (pagesScan != 2) {
            // Mode volumineux (critère de sortie E6 : scan de 20 pages) : scans seuls.
            for (String[] s : new String[][]{{"scan_fr", "fr"}, {"scan_ar", "ar"}}) {
                Path p = sortie.resolve(s[0] + "_" + pagesScan + "p.pdf");
                boolean ar = s[1].equals("ar");
                pdfScanne(p, ar ? COURRIER_AR : COURRIER_FR, ar, baseArabe, pagesScan);
                out.println(p.getFileName() + "  " + Files.size(p) + " octets  sha256=" + sha256(p));
            }
            return;
        }

        Map<String, String[]> attendus = new LinkedHashMap<>();
        pdfTexte(sortie.resolve("pdf_texte_fr_convention.pdf"), "Convention de partenariat — lagune de Marchica", COURRIER_FR);
        attendus.put("pdf_texte_fr_convention.pdf", new String[]{"application/pdf", "201 (ou 202 si OCR en attente)", "Couche texte native ; témoin « " + TEMOIN_FR + " »"});
        pdfTexte(sortie.resolve("pdf_texte_fr_facture.pdf"), "Facture n° F-2026-0914", List.of(
                "Fournisseur : Société fictive de travaux lagunaires SARL", "Date de facture : 14/09/2026",
                "Désignation : aménagement des berges, lot 3, situation n° 14",
                "Montant HT : 850 000,00 MAD — TVA 20 % : 170 000,00 MAD — Montant TTC : 1 020 000,00 MAD",
                "Référence de recette : " + TEMOIN_FR + "-facture"));
        attendus.put("pdf_texte_fr_facture.pdf", new String[]{"application/pdf", "201 (ou 202)", "Couche texte native, montants et dates"});
        pdfTexteArabe(sortie.resolve("pdf_texte_ar_courrier.pdf"), police);
        attendus.put("pdf_texte_ar_courrier.pdf", new String[]{"application/pdf", "201 (ou 202)", "Couche texte arabe (formes de présentation Unicode, ordre visuel) ; témoin « " + TEMOIN_AR + " »"});
        pdfScanne(sortie.resolve("scan_fr_courrier.pdf"), COURRIER_FR, false, baseArabe, 2);
        attendus.put("scan_fr_courrier.pdf", new String[]{"application/pdf", "202 EN_ATTENTE_OCR", "Images seules 300 dpi, aucune couche texte ; témoin « " + TEMOIN_FR + " » après OCR fra"});
        pdfScanne(sortie.resolve("scan_ar_courrier.pdf"), COURRIER_AR, true, baseArabe, 2);
        attendus.put("scan_ar_courrier.pdf", new String[]{"application/pdf", "202 EN_ATTENTE_OCR", "Images seules 300 dpi, arabe ; témoin « " + TEMOIN_AR + " » après OCR ara"});
        pngScan(sortie.resolve("image_scan_fr.png"));
        attendus.put("image_scan_fr.png", new String[]{"image/png", "201 (ou 202)", "Image 200 dpi, OCR fra"});
        docx(sortie.resolve("document_fr.docx"));
        attendus.put("document_fr.docx", new String[]{"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "201", "Témoin « " + TEMOIN_FR + "-docx » ; prévisualisation via LibreOffice"});
        Files.writeString(sortie.resolve("note_texte_brut.txt"), "Note interne de recette (" + TEMOIN_FR + "-txt).\n"
                + "Format texte brut autorisé par défaut (6.1.5).\n", StandardCharsets.UTF_8);
        attendus.put("note_texte_brut.txt", new String[]{"text/plain", "201 si le type l'autorise", "Format autorisé par défaut"});
        Files.writeString(sortie.resolve("tableau_decomptes.csv"), "numero;date;montant_mad;statut\n"
                + "12;2026-07-31;812000.00;payé\n13;2026-08-31;905500.00;payé\n14;2026-09-25;1020000.00;en visa\n", StandardCharsets.UTF_8);
        attendus.put("tableau_decomptes.csv", new String[]{"text/csv", "201 si le type l'autorise", "Format autorisé par défaut"});
        // Extension .pdf, contenu texte : Tika détecte text/plain → 415 sur un type « PDF seul ».
        Files.writeString(sortie.resolve("faux_pdf_texte.pdf"), "Ceci n'est pas un PDF. Fichier de recette : extension .pdf, "
                + "contenu texte brut.\nLe type réel doit être déterminé par le contenu (DAT V3, 6.1.5).\n", StandardCharsets.UTF_8);
        attendus.put("faux_pdf_texte.pdf", new String[]{"text/plain", "415", "Extension .pdf, contenu texte : refus par type réel"});
        Files.write(sortie.resolve("faux_pdf_executable.pdf"), enteteExecutable());
        attendus.put("faux_pdf_executable.pdf", new String[]{"application/x-msdownload", "415", "Extension .pdf, en-tête MZ/PE : refus par type réel"});

        StringBuilder manifeste = new StringBuilder("fichier;octets;sha256;type_reel_attendu;reponse_depot_attendue;remarque\n");
        for (Map.Entry<String, String[]> e : attendus.entrySet()) {
            Path p = sortie.resolve(e.getKey());
            manifeste.append(e.getKey()).append(';').append(Files.size(p)).append(';').append(sha256(p)).append(';')
                    .append(String.join(";", e.getValue())).append('\n');
            out.printf("%-32s %9d octets%n", e.getKey(), Files.size(p));
        }
        Files.writeString(sortie.resolve("MANIFESTE.csv"), manifeste, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ PDF texte

    static void pdfTexte(Path sortie, String titre, List<String> lignes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int n = 1; n <= 2; n++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = page.getMediaBox().getHeight() - 70;
                    cs.beginText();
                    cs.setFont(gras, 13);
                    cs.newLineAtOffset(60, y);
                    cs.showText(titre);
                    cs.setFont(normal, 10.5f);
                    cs.newLineAtOffset(0, -30);
                    for (String l : lignes) {
                        for (String morceau : couper(l, 95)) {
                            cs.showText(morceau);
                            cs.newLineAtOffset(0, -15);
                        }
                        cs.newLineAtOffset(0, -6);
                    }
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(60, 50);
                    cs.showText("Page " + n + " / 2");
                    cs.endText();
                }
            }
            doc.getDocumentInformation().setTitle(titre);
            doc.getDocumentInformation().setAuthor("Recette GED — données fictives");
            enregistrer(doc, sortie);
        }
    }

    static List<String> couper(String texte, int largeur) {
        List<String> r = new ArrayList<>();
        StringBuilder ligne = new StringBuilder();
        for (String mot : texte.split(" ")) {
            if (ligne.length() + mot.length() + 1 > largeur && ligne.length() > 0) {
                r.add(ligne.toString());
                ligne.setLength(0);
            }
            if (ligne.length() > 0) {
                ligne.append(' ');
            }
            ligne.append(mot);
        }
        r.add(ligne.toString());
        return r;
    }

    /**
     * PDF à couche texte arabe. PDFBox ne met pas l'arabe en forme : on écrit donc
     * directement les formes de présentation Unicode en ordre visuel, exactement ce que
     * produisent beaucoup d'outils bureautiques — un cas réel et difficile pour la
     * recherche plein texte (normalisation NFKC nécessaire).
     */
    static void pdfTexteArabe(Path sortie, File police) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font f = PDType0Font.load(doc, police);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float largeurPage = page.getMediaBox().getWidth();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - 80;
                for (String paragraphe : COURRIER_AR) {
                    for (String ligne : couper(paragraphe, 70)) {
                        String visuel = FormesArabes.visuel(ligne);
                        float l = f.getStringWidth(visuel) / 1000 * 12;
                        cs.beginText();
                        cs.setFont(f, 12);
                        cs.newLineAtOffset(largeurPage - 60 - l, y);
                        cs.showText(visuel);
                        cs.endText();
                        y -= 18;
                    }
                    y -= 8;
                }
            }
            doc.getDocumentInformation().setTitle("Courrier arabe — couche texte native");
            doc.getDocumentInformation().setAuthor("Recette GED — données fictives");
            enregistrer(doc, sortie);
        }
    }

    // ------------------------------------------------------------------ scans

    /** Page A4 rendue par Java2D à la résolution demandée (paragraphes justifiés à gauche, ou à droite en RTL). */
    static BufferedImage rendrePage(List<String> lignes, boolean rtl, Font baseArabe, int dpi, int numero, int total) {
        int l = Math.round(8.27f * dpi), h = Math.round(11.69f * dpi);
        BufferedImage img = new BufferedImage(l, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l, h);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float taille = 12f * dpi / 72f;
        Font police = rtl ? baseArabe.deriveFont(taille) : new Font("SansSerif", Font.PLAIN, 1).deriveFont(taille);
        FontRenderContext frc = g.getFontRenderContext();
        float marge = 60f * dpi / 72f, largeur = l - 2 * marge, y = marge;
        List<String> tout = new ArrayList<>(lignes);
        // Marqueur propre à la page (zarkopage01, zarkopage02…) : retrouver « zarkopage20 » prouve que
        // la 20e page a été OCRisée (aucun plafond de pages, DAT §4.3.4).
        String marqueur = String.format("zarkopage%02d", numero);
        tout.add(rtl ? "الصفحة " + numero + " من " + total + " " + marqueur : "— " + numero + " / " + total + " — " + marqueur);
        for (String paragraphe : tout) {
            AttributedString as = new AttributedString(paragraphe);
            as.addAttribute(TextAttribute.FONT, police);
            as.addAttribute(TextAttribute.RUN_DIRECTION, rtl ? TextAttribute.RUN_DIRECTION_RTL : TextAttribute.RUN_DIRECTION_LTR);
            LineBreakMeasurer mesure = new LineBreakMeasurer(as.getIterator(), frc);
            while (mesure.getPosition() < paragraphe.length()) {
                TextLayout tl = mesure.nextLayout(largeur);
                y += tl.getAscent();
                float x = rtl ? marge + largeur - tl.getAdvance() : marge;
                tl.draw(g, x, y);
                y += tl.getDescent() + tl.getLeading() + taille * 0.5f;
            }
            y += taille * 0.75f;
        }
        g.dispose();
        return img;
    }

    /** Imite un scanner de bureau : léger biais, fond gris, poussières, flou. Réaliste, pas piégeux. */
    static BufferedImage degrader(BufferedImage src, int graine) {
        Random r = new Random(graine);
        BufferedImage tourne = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = tourne.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, AffineTransform.getRotateInstance(Math.toRadians(graine % 2 == 1 ? 0.6 : -0.5),
                src.getWidth() / 2.0, src.getHeight() / 2.0), null);
        g.dispose();
        var raster = tourne.getRaster();
        for (int y = 0; y < tourne.getHeight(); y++) {
            for (int x = 0; x < tourne.getWidth(); x++) {
                int v = raster.getSample(x, y, 0) - 12;
                double t = r.nextDouble();
                if (t < 0.0015) {
                    v = 40;
                } else if (t > 0.9985) {
                    v = 255;
                }
                raster.setSample(x, y, 0, Math.max(0, Math.min(255, v)));
            }
        }
        float[] noyau = {0.0625f, 0.125f, 0.0625f, 0.125f, 0.25f, 0.125f, 0.0625f, 0.125f, 0.0625f};
        return new ConvolveOp(new Kernel(3, 3, noyau), ConvolveOp.EDGE_NO_OP, null).filter(tourne, null);
    }

    static byte[] jpeg(BufferedImage img, float qualite) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(qualite);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(b)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
        return b.toByteArray();
    }

    /** PDF composé uniquement d'images (aucune couche texte), comme un vrai scan. */
    static void pdfScanne(Path sortie, List<String> lignes, boolean rtl, Font baseArabe, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int n = 1; n <= pages; n++) {
                BufferedImage img = degrader(rendrePage(lignes, rtl, baseArabe, DPI_SCAN, n, pages), n);
                PDImageXObject x = JPEGFactory.createFromByteArray(doc, jpeg(img, 0.72f));
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            enregistrer(doc, sortie);
        }
        // Garde-fou : un « scan » qui porterait une couche texte fausserait le test OCR.
        try (PDDocument verif = Loader.loadPDF(sortie.toFile())) {
            if (!new PDFTextStripper().getText(verif).isBlank()) {
                throw new IllegalStateException(sortie + " contient une couche texte : ce n'est pas un scan");
            }
        }
    }

    static void pngScan(Path sortie) throws IOException {
        BufferedImage page = rendrePage(COURRIER_FR.subList(0, 7), false, null, 200, 1, 1);
        BufferedImage haut = page.getSubimage(0, 0, page.getWidth(), page.getHeight() * 45 / 100);
        ImageIO.write(degrader(haut, 7), "png", sortie.toFile());
    }

    // ------------------------------------------------------------------ DOCX

    /**
     * DOCX minimal écrit à la main (quatre parties OOXML) : pas de dépendance Apache POI,
     * et des dates d'entrée ZIP fixes pour que le fichier soit identique d'une
     * génération à l'autre.
     */
    static void docx(Path sortie) throws IOException {
        String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        StringBuilder corps = new StringBuilder();
        corps.append(para("Procès-verbal de la réunion de coordination du 12 septembre 2026", true));
        corps.append(para("Référence de recette : " + TEMOIN_FR + "-docx", false));
        corps.append(para("Présents : la Direction Technique, la Direction Administrative et Financière, le bureau "
                + "d'études et l'entreprise titulaire du lot 3 (berges et sentier).", false));
        corps.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>");
        String[][] lignes = {{"Point", "Décision", "Échéance"}, {"Planning", "Validé avec réserve", "30/09/2026"},
                {"Décompte n° 14", "Transmis pour visa", "05/10/2026"}, {"Sentier pédestre", "Tracé définitif arrêté", "15/10/2026"}};
        for (String[] l : lignes) {
            corps.append("<w:tr>");
            for (String c : l) {
                corps.append("<w:tc>").append(para(c, false)).append("</w:tc>");
            }
            corps.append("</w:tr>");
        }
        corps.append("</w:tbl>");
        Map<String, String> parties = new LinkedHashMap<>();
        parties.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>");
        parties.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
        parties.put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + w + "\"><w:body>" + corps + "<w:sectPr/></w:body></w:document>");
        parties.put("docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Procès-verbal de réunion de coordination</dc:title>"
                + "<dc:creator>Recette GED — données fictives</dc:creator></cp:coreProperties>");
        try (OutputStream fo = Files.newOutputStream(sortie); ZipOutputStream z = new ZipOutputStream(fo)) {
            for (Map.Entry<String, String> e : parties.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(1790380800000L); // 2026-09-26 : octets stables d'une génération à l'autre
                z.putNextEntry(ze);
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    static String para(String texte, boolean gras) {
        String t = texte.replace("&", "&amp;").replace("<", "&lt;");
        return "<w:p><w:r>" + (gras ? "<w:rPr><w:b/></w:rPr>" : "") + "<w:t xml:space=\"preserve\">" + t + "</w:t></w:r></w:p>";
    }

    // ------------------------------------------------------------------ divers

    /**
     * Enregistre avec un identifiant de fichier (/ID) dérivé du nom : sans lui, PDFBox en
     * tire un au hasard et le fichier, donc son empreinte au manifeste, changerait à
     * chaque génération.
     */
    static void enregistrer(PDDocument doc, Path sortie) throws IOException {
        byte[] id;
        try {
            id = java.util.Arrays.copyOf(MessageDigest.getInstance("SHA-256")
                    .digest(sortie.getFileName().toString().getBytes(StandardCharsets.UTF_8)), 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        COSArray ids = new COSArray();
        ids.add(new COSString(id));
        ids.add(new COSString(id));
        doc.getDocument().getTrailer().setItem(COSName.ID, ids);
        doc.save(sortie.toFile());
    }

    /** En-tête MZ/PE minimal, sans aucun code : reconnu comme exécutable par Tika, inoffensif. */
    static byte[] enteteExecutable() {
        byte[] b = new byte[512];
        b[0] = 'M';
        b[1] = 'Z';
        b[0x3C] = (byte) 0x80;
        b[0x80] = 'P';
        b[0x81] = 'E';
        b[0x84] = 0x4C;
        b[0x85] = 0x01;
        byte[] msg = "This program cannot be run in DOS mode.".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(msg, 0, b, 0x40, msg.length);
        return b;
    }

    static File trouverPolice(String imposee) {
        for (String c : imposee != null ? new String[]{imposee} : POLICES_ARABES) {
            File f = new File(c);
            if (f.isFile()) {
                return f;
            }
        }
        throw new IllegalStateException("Aucune police couvrant l'arabe : passer --police-arabe <fichier.ttf>");
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /**
     * Mise en forme arabe minimale pour la couche texte PDF : formes contextuelles
     * (isolée, finale, initiale, médiane) du bloc « Arabic Presentation Forms-B », ligatures
     * lam-alef, puis ordre visuel (inversion, les nombres gardant leur sens).
     */
    static final class FormesArabes {
        // lettre → {isolée, finale, initiale, médiane} ; initiale/médiane à 0 : lettre non liante vers la gauche.
        static final Map<Character, char[]> FORMES = new LinkedHashMap<>();

        static {
            f('ء', 0xFE80, 0, 0, 0);
            f('آ', 0xFE81, 0xFE82, 0, 0);
            f('أ', 0xFE83, 0xFE84, 0, 0);
            f('ؤ', 0xFE85, 0xFE86, 0, 0);
            f('إ', 0xFE87, 0xFE88, 0, 0);
            f('ئ', 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C);
            f('ا', 0xFE8D, 0xFE8E, 0, 0);
            f('ب', 0xFE8F, 0xFE90, 0xFE91, 0xFE92);
            f('ة', 0xFE93, 0xFE94, 0, 0);
            f('ت', 0xFE95, 0xFE96, 0xFE97, 0xFE98);
            f('ث', 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C);
            f('ج', 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0);
            f('ح', 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4);
            f('خ', 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8);
            f('د', 0xFEA9, 0xFEAA, 0, 0);
            f('ذ', 0xFEAB, 0xFEAC, 0, 0);
            f('ر', 0xFEAD, 0xFEAE, 0, 0);
            f('ز', 0xFEAF, 0xFEB0, 0, 0);
            f('س', 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4);
            f('ش', 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8);
            f('ص', 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC);
            f('ض', 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0);
            f('ط', 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4);
            f('ظ', 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8);
            f('ع', 0xFEC9, 0xFECA, 0xFECB, 0xFECC);
            f('غ', 0xFECD, 0xFECE, 0xFECF, 0xFED0);
            f('ف', 0xFED1, 0xFED2, 0xFED3, 0xFED4);
            f('ق', 0xFED5, 0xFED6, 0xFED7, 0xFED8);
            f('ك', 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC);
            f('ل', 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0);
            f('م', 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4);
            f('ن', 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8);
            f('ه', 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC);
            f('و', 0xFEED, 0xFEEE, 0, 0);
            f('ى', 0xFEEF, 0xFEF0, 0, 0);
            f('ي', 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4);
        }

        static void f(char c, int iso, int fin, int ini, int med) {
            FORMES.put(c, new char[]{(char) iso, (char) fin, (char) ini, (char) med});
        }

        static boolean lieAGauche(char c) {
            char[] x = FORMES.get(c);
            return x != null && x[2] != 0;
        }

        static String visuel(String logique) {
            StringBuilder formes = new StringBuilder();
            char[] t = logique.toCharArray();
            for (int i = 0; i < t.length; i++) {
                char c = t[i];
                char[] x = FORMES.get(c);
                if (x == null) {
                    formes.append(c);
                    continue;
                }
                boolean avant = i > 0 && lieAGauche(t[i - 1]);
                // Ligature lam-alef.
                if (c == 'ل' && i + 1 < t.length && "آأإا".indexOf(t[i + 1]) >= 0) {
                    int base = switch (t[i + 1]) {
                        case 'آ' -> 0xFEF5;
                        case 'أ' -> 0xFEF7;
                        case 'إ' -> 0xFEF9;
                        default -> 0xFEFB;
                    };
                    formes.append((char) (base + (avant ? 1 : 0)));
                    i++;
                    continue;
                }
                boolean apres = i + 1 < t.length && FORMES.containsKey(t[i + 1]) && lieAGauche(c);
                char forme = avant && apres ? x[3] : avant ? x[1] : apres ? x[2] : x[0];
                formes.append(forme == 0 ? x[0] : forme);
            }
            // Ordre visuel : on inverse, puis on remet à l'endroit les suites de chiffres et de latin.
            String inverse = formes.reverse().toString();
            StringBuilder r = new StringBuilder();
            StringBuilder ltr = new StringBuilder();
            for (char c : inverse.toCharArray()) {
                if (c < 0x0590 && (Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == ',')) {
                    ltr.append(c);
                } else {
                    r.append(ltr.reverse());
                    ltr.setLength(0);
                    r.append(c);
                }
            }
            return r.append(ltr.reverse()).toString();
        }
    }
}
