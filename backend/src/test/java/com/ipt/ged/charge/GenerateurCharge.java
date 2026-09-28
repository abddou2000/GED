package com.ipt.ged.charge;

import com.ipt.ged.ocr.moteur.Scans;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Jeu synthétique des essais de charge : pages « scannées » denses (A4,
 * 300 dpi, niveaux de gris, JPEG comme un numériseur), en français ou en
 * arabe ; PDF volumineux incompressibles ; textes d'indexation.
 */
final class GenerateurCharge {

    private GenerateurCharge() {
    }

    enum Langue { FR, AR }

    private static final String[] MOTS_FR = ("contrat convention marché avenant facture paiement délai lot travaux "
            + "fourniture prestation maître ouvrage entreprise titulaire montant garantie réception définitive "
            + "provisoire pénalité retard article clause résiliation cahier charges administratives particulières "
            + "bordereau prix unitaires détail estimatif ordre service décompte situation attachement lagune "
            + "aménagement Marchica Nador environnement études ingénierie contrôle qualité sécurité chantier").split(" ");
    private static final String[] MOTS_AR = ("عقد اتفاقية صفقة ملحق فاتورة أداء أجل حصة أشغال توريد خدمة صاحب "
            + "المشروع مقاولة مبلغ ضمان تسلم نهائي مؤقت غرامة تأخير فصل بند فسخ دفتر التحملات الإدارية الخاصة "
            + "جدول الأثمان تفصيل تقديري أمر بالخدمة كشف حساب وضعية بحيرة تهيئة مارتشيكا الناظور البيئة دراسات "
            + "هندسة مراقبة الجودة السلامة ورش").split(" ");

    static String phrase(Random r, Langue l, int mots) {
        String[] v = l == Langue.FR ? MOTS_FR : MOTS_AR;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mots; i++) {
            if (i > 0) sb.append(' ');
            sb.append(v[r.nextInt(v.length)]);
        }
        return sb.toString();
    }

    /** Page A4 dense à 300 dpi : ~40 lignes de texte (courrier ou pièce de marché). */
    static BufferedImage page(Random r, Langue l, int numero) {
        int largeur = 2480, hauteur = 3508;
        BufferedImage img = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, largeur, hauteur);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String police = l == Langue.AR ? Scans.policeArabe().orElse("Tahoma") : "Arial";
        g.setFont(new Font(police, Font.PLAIN, 44));
        int y = 260;
        g.drawString((l == Langue.FR ? "Page " : "صفحة ") + numero, l == Langue.FR ? 220 : largeur - 420, 180);
        for (int i = 0; i < 40 && y < hauteur - 200; i++) {
            String ligne = phrase(r, l, 9);
            int x = l == Langue.AR ? largeur - 220 - g.getFontMetrics().stringWidth(ligne) : 220;
            g.drawString(ligne, x, y);
            y += 78;
        }
        g.dispose();
        return img;
    }

    /** PDF image seule de {@code pages} pages, JPEG qualité 0,75 en niveaux de gris. */
    static byte[] scan(long graine, Langue l, int pages) throws IOException {
        return scan(graine, l, pages, false);
    }

    /**
     * PDF image seule ; {@code noirEtBlanc} : images 1 bit compressées CCITT G4,
     * format habituel des numériseurs de production pour les documents
     * administratifs (~50 Ko par page au lieu de ~850 Ko en gris).
     */
    static byte[] scan(long graine, Langue l, int pages, boolean noirEtBlanc) throws IOException {
        Random r = new Random(graine);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage img = page(r, l, i);
                PDImageXObject x = noirEtBlanc ? CCITTFactory.createFromImage(doc, binaire(img))
                        : JPEGFactory.createFromImage(doc, img, 0.75f);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage binaire(BufferedImage gris) {
        BufferedImage b = new BufferedImage(gris.getWidth(), gris.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = b.createGraphics();
        g.drawImage(gris, 0, 0, null);
        g.dispose();
        return b;
    }

    /** PDF d'une page image à 150 dpi (pièce courante, ~100 Ko). */
    static byte[] scanLeger(long graine) throws IOException {
        Random r = new Random(graine);
        BufferedImage p = page(r, Langue.FR, 1);
        BufferedImage petite = new BufferedImage(1240, 1754, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = petite.createGraphics();
        g.drawImage(p, 0, 0, 1240, 1754, null);
        g.dispose();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject x = JPEGFactory.createFromImage(doc, petite, 0.75f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * PDF valide d'environ {@code octets} octets, incompressible (flux d'octets
     * aléatoires) : pire cas pour le chiffrement et la compression du ZIP.
     */
    static byte[] pdfVolumineux(long graine, int octets) {
        byte[] alea = new byte[octets];
        new Random(graine).nextBytes(alea);
        String entete = "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
                + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >> endobj\n"
                + "4 0 obj << /Length " + octets + " >> stream\n";
        String fin = "\nendstream endobj\ntrailer << /Root 1 0 R /Size 5 >>\n%%EOF\n";
        byte[] a = entete.getBytes(StandardCharsets.ISO_8859_1), b = fin.getBytes(StandardCharsets.ISO_8859_1);
        byte[] tout = new byte[a.length + octets + b.length];
        System.arraycopy(a, 0, tout, 0, a.length);
        System.arraycopy(alea, 0, tout, a.length, octets);
        System.arraycopy(b, 0, tout, a.length + octets, b.length);
        return tout;
    }

    /** Texte d'un document indexé (~{@code mots} mots, 70 % français, 30 % arabe). */
    static String texte(Random r, int mots) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < mots / 10; i++) {
            l.add(phrase(r, r.nextInt(10) < 7 ? Langue.FR : Langue.AR, 10));
        }
        return String.join(". ", l);
    }

    /* ---------- texte OCR réaliste pour la recherche ---------- */

    /**
     * Lexique de 40 000 formes : les mots du fonds en tête (les plus
     * fréquents), puis des mots artificiels français (30 000) et arabes
     * (10 000) bâtis sur des syllabes. Tirage selon une loi de Zipf (s = 1),
     * comme la fréquence des mots d'une langue : un terme courant figure dans
     * presque tous les documents, un terme rare dans quelques-uns. Avec un
     * vocabulaire uniforme d'une centaine de mots, chaque requête ramènerait
     * tout le fonds et la mesure ne dirait rien des requêtes sélectives.
     */
    static final class Lexique {
        static final List<String> FORMES = new ArrayList<>();
        private static final double[] CUMUL;
        private static final String[] SYL_FR = ("ba be bi bo bu ca ce ci co cu da de di do du fa fe fi fo ga ge gi go "
                + "la le li lo lu ma me mi mo mu na ne ni no nu pa pe pi po pu ra re ri ro ru sa se si so su ta te ti "
                + "to tu va ve vi vo tion ment eur ais ant ier age ure").split(" ");
        private static final String[] SYL_AR = ("با بو بي تا تو تي جا جو دا دو را رو ري سا سو سي شا عا عو فا فو قا "
                + "قو كا كو لا لو لي ما مو مي نا نو ني ها هو وا وي يا يو ة ات ين ون").split(" ");

        static {
            List<String> base = new ArrayList<>();
            java.util.Collections.addAll(base, MOTS_FR);
            java.util.Collections.addAll(base, MOTS_AR);
            java.util.LinkedHashSet<String> formes = new java.util.LinkedHashSet<>(base);
            Random r = new Random(2026);
            while (formes.size() < base.size() + 30_000) formes.add(mot(r, SYL_FR));
            while (formes.size() < base.size() + 40_000) formes.add(mot(r, SYL_AR));
            FORMES.addAll(formes);
            // Rangs mélangés au-delà des mots du fonds : les formes arabes ne
            // doivent pas être toutes rares.
            java.util.Collections.shuffle(FORMES.subList(base.size(), FORMES.size()), new Random(7));
            CUMUL = new double[FORMES.size()];
            double s = 0;
            for (int i = 0; i < CUMUL.length; i++) {
                s += 1.0 / (i + 1);
                CUMUL[i] = s;
            }
            for (int i = 0; i < CUMUL.length; i++) CUMUL[i] /= s;
        }

        private static String mot(Random r, String[] syllabes) {
            StringBuilder sb = new StringBuilder();
            int n = 2 + r.nextInt(3);
            for (int i = 0; i < n; i++) sb.append(syllabes[r.nextInt(syllabes.length)]);
            return sb.toString();
        }

        static String tirer(Random r) {
            int i = java.util.Arrays.binarySearch(CUMUL, r.nextDouble());
            return FORMES.get(i >= 0 ? i : Math.min(-i - 1, FORMES.size() - 1));
        }

        /** Forme de rang donné (0 = la plus fréquente). */
        static String rang(int rang) {
            return FORMES.get(rang);
        }
    }

    /**
     * Texte OCR d'un document de {@code pages} pages : ~430 mots (~3 Ko) par
     * page, hypothèse du §6.6.
     */
    static String texteRealiste(Random r, int pages) {
        StringBuilder sb = new StringBuilder(pages * 3200);
        int mots = pages * 430;
        for (int i = 0; i < mots; i++) {
            if (i > 0) sb.append(i % 12 == 0 ? ".\n" : " ");
            sb.append(Lexique.tirer(r));
        }
        return sb.toString();
    }

    /** Pages d'un document : 1 à 40, 8 en moyenne (§6.6), loi géométrique. */
    static int pagesDocument(Random r) {
        int p = 1;
        while (p < 40 && r.nextDouble() < 7.0 / 8) p++;
        return p;
    }
}
