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
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Corpus OCR à vérité connue (§4.3.2) : pages A4 à 300 dpi dont le texte exact
 * est conservé, en français, en arabe ou bilingues, à trois qualités de
 * numérisation. Sert à mesurer la qualité (CER, WER) en même temps que le
 * débit, sans Python (décision D5).
 *
 * <p>Limite assumée : texte composé par Java2D avec des polices système, sur
 * un vocabulaire administratif réduit. C'est plus favorable que le fonds réel
 * de MMED (tampons, manuscrit, tableaux, photocopies) : les CER mesurés ici
 * sont des planchers, le protocole du §4.3.2 sur l'échantillon réel reste dû.
 */
final class CorpusOcr {

    private CorpusOcr() {
    }

    enum Langue { FR, AR, MIXTE }

    /**
     * PROPRE : scan niveaux de gris JPEG 0,75 d'un original imprimé ;
     * NB : scan 1 bit CCITT G4, réglage par défaut des numériseurs de bureau
     * d'ordre ; DEGRADE : photocopie de photocopie (inclinaison 1 à 1,5°, fond
     * grisé, bruit, flou, taches, JPEG 0,5).
     */
    enum Qualite { PROPRE, NB, DEGRADE }

    record Page(Langue langue, Qualite qualite, int numero, List<String> lignes, byte[] pdf) {
        String verite() {
            return String.join("\n", lignes);
        }
    }

    private static final String[] MOTS_FR = ("contrat convention marché avenant facture paiement délai lot travaux "
            + "fourniture prestation maître d'ouvrage entreprise titulaire montant garantie réception définitive "
            + "provisoire pénalité retard article clause résiliation cahier des charges administratives particulières "
            + "bordereau des prix unitaires détail estimatif ordre de service décompte situation attachement lagune "
            + "aménagement Marchica Nador environnement études ingénierie contrôle qualité sécurité chantier "
            + "procès-verbal réunion commission ouverture plis soumissionnaire caution retenue échéance "
            + "l'agence société anonyme courrier arrivée départ référence objet destinataire signataire").split(" ");
    private static final String[] MOTS_AR = ("عقد اتفاقية صفقة ملحق فاتورة أداء أجل حصة أشغال توريد خدمة صاحب "
            + "المشروع مقاولة مبلغ ضمان تسلم نهائي مؤقت غرامة تأخير فصل بند فسخ دفتر التحملات الإدارية الخاصة "
            + "جدول الأثمان تفصيل تقديري أمر بالخدمة كشف حساب وضعية بحيرة تهيئة مارتشيكا الناظور البيئة دراسات "
            + "هندسة مراقبة الجودة السلامة ورش محضر اجتماع لجنة فتح الأظرفة المتنافس كفالة اقتطاع "
            + "الوكالة شركة مراسلة واردة صادرة مرجع موضوع المرسل إليه الموقع").split(" ");
    private static final String[] MOIS = {"janvier", "février", "mars", "avril", "mai", "juin", "juillet",
            "septembre", "octobre", "novembre", "décembre"};

    /** Ligne française : mots du fonds, montants, dates, références et ponctuation. */
    static String ligneFr(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = 7 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            int tirage = r.nextInt(20);
            if (tirage == 0) {
                sb.append(String.format(Locale.FRANCE, "%,d,%02d", 1000 + r.nextInt(9_000_000), r.nextInt(100))
                        .replace(' ', ' ').replace(' ', ' ')).append(" DH");
            } else if (tirage == 1) {
                sb.append(1 + r.nextInt(28)).append(' ').append(MOIS[r.nextInt(MOIS.length)]).append(' ')
                        .append(2019 + r.nextInt(8));
            } else if (tirage == 2) {
                sb.append("N° ").append(String.format("%02d/AO/MM/%d", 1 + r.nextInt(40), 20 + r.nextInt(7)));
            } else {
                String m = MOTS_FR[r.nextInt(MOTS_FR.length)];
                sb.append(i == 0 ? Character.toUpperCase(m.charAt(0)) + m.substring(1) : m);
            }
            if (i < n - 1 && r.nextInt(9) == 0) sb.append(',');
        }
        return sb.append(r.nextInt(3) == 0 ? " ;" : ".").toString();
    }

    /** Ligne arabe : mots du fonds seulement (pas de chiffres : l'ordre bidi d'une vérité mixte serait ambigu). */
    static String ligneAr(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = 7 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(MOTS_AR[r.nextInt(MOTS_AR.length)]);
            if (i < n - 1 && r.nextInt(9) == 0) sb.append('،');
        }
        return sb.append('.').toString();
    }

    static List<Page> pages(Langue langue, Qualite qualite, int nombre, long graine) throws IOException {
        List<Page> l = new ArrayList<>();
        for (int i = 0; i < nombre; i++) {
            Random r = new Random(graine * 1000 + i * 7L + langue.ordinal() * 31L);
            List<String> lignes = new ArrayList<>();
            int nbLignes = 34 + r.nextInt(6);
            for (int k = 0; k < nbLignes; k++) {
                boolean ar = langue == Langue.AR || (langue == Langue.MIXTE && k % 2 == 1);
                lignes.add(ar ? ligneAr(r) : ligneFr(r));
            }
            BufferedImage img = image(lignes, qualite, r);
            l.add(new Page(langue, qualite, i, lignes, pdf(img, qualite)));
        }
        return l;
    }

    /** Page A4 à 300 dpi (2480 × 3508), corps 11 pt ; l'arabe est aligné à droite. */
    static BufferedImage image(List<String> lignes, Qualite qualite, Random r) {
        int largeur = 2480, hauteur = 3508;
        BufferedImage img = new BufferedImage(largeur, hauteur, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(qualite == Qualite.DEGRADE ? new Color(228, 228, 228) : Color.WHITE);
        g.fillRect(0, 0, largeur, hauteur);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (qualite == Qualite.DEGRADE) {
            double angle = Math.toRadians((1 + r.nextDouble() * 0.5) * (r.nextBoolean() ? 1 : -1));
            g.setTransform(AffineTransform.getRotateInstance(angle, largeur / 2.0, hauteur / 2.0));
        }
        String arabe = Scans.policeArabe().orElse("Tahoma");
        String latine = qualite == Qualite.DEGRADE ? "Times New Roman" : "Arial";
        g.setColor(qualite == Qualite.DEGRADE ? new Color(55, 55, 55) : Color.BLACK);
        int y = 260;
        for (String ligne : lignes) {
            boolean ar = ligne.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);
            g.setFont(new Font(ar ? arabe : latine, Font.PLAIN, 44));
            int x = ar ? largeur - 220 - g.getFontMetrics().stringWidth(ligne) : 220;
            g.drawString(ligne, x, y);
            y += 82;
        }
        g.dispose();
        if (qualite == Qualite.DEGRADE) {
            img = degrader(img, r);
        }
        return img;
    }

    /** Flou léger, bruit gaussien et taches, comme une photocopie usée. */
    private static BufferedImage degrader(BufferedImage src, Random r) {
        float[] k = new float[9];
        java.util.Arrays.fill(k, 1f / 9);
        BufferedImage flou = new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
        var raster = flou.getRaster();
        int w = flou.getWidth(), h = flou.getHeight();
        int[] ligne = new int[w];
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, ligne);
            for (int x = 0; x < w; x++) {
                int v = ligne[x] + (int) Math.round(r.nextGaussian() * 18);
                ligne[x] = Math.max(0, Math.min(255, v));
            }
            raster.setSamples(0, y, w, 1, 0, ligne);
        }
        Graphics2D g = flou.createGraphics();
        g.setColor(new Color(70, 70, 70));
        for (int i = 0; i < 1500; i++) {
            int t = 1 + r.nextInt(4);
            g.fillOval(r.nextInt(w), r.nextInt(h), t, t);
        }
        g.dispose();
        return flou;
    }

    /** PDF image seule d'une page A4, comme en produit un numériseur. */
    static byte[] pdf(BufferedImage img, Qualite qualite) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ajouterPage(doc, img, qualite);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void ajouterPage(PDDocument doc, BufferedImage img, Qualite qualite) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject x = switch (qualite) {
            case NB -> CCITTFactory.createFromImage(doc, binaire(img));
            case PROPRE -> JPEGFactory.createFromImage(doc, img, 0.75f);
            case DEGRADE -> JPEGFactory.createFromImage(doc, img, 0.5f);
        };
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
        }
    }

    /** PDF scanné multi-pages (courrier, pièce de marché, attachement de 400 pages). */
    static byte[] document(Langue langue, Qualite qualite, int pages, long graine) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                Random r = new Random(graine * 100_000 + i);
                List<String> lignes = new ArrayList<>();
                int nbLignes = 34 + r.nextInt(6);
                for (int k = 0; k < nbLignes; k++) {
                    boolean ar = langue == Langue.AR || (langue == Langue.MIXTE && k % 2 == 1);
                    lignes.add(ar ? ligneAr(r) : ligneFr(r));
                }
                ajouterPage(doc, image(lignes, qualite, r), qualite);
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

    /* ---------- métriques de qualité (§4.3.2) ---------- */

    /** Taux d'erreur caractère : distance d'édition / longueur de la vérité, blancs normalisés. */
    static double cer(String verite, String reconnu) {
        String a = normaliser(verite), b = normaliser(reconnu);
        return a.isEmpty() ? 0 : (double) distance(a.codePoints().toArray(), b.codePoints().toArray()) / a.codePoints().count();
    }

    /** Taux d'erreur mot : même distance, calculée sur les mots. */
    static double wer(String verite, String reconnu) {
        String[] a = normaliser(verite).split(" "), b = normaliser(reconnu).split(" ");
        java.util.Map<String, Integer> dico = new java.util.HashMap<>();
        int[] ia = new int[a.length], ib = new int[b.length];
        for (int i = 0; i < a.length; i++) ia[i] = dico.computeIfAbsent(a[i], x -> dico.size());
        for (int i = 0; i < b.length; i++) ib[i] = dico.computeIfAbsent(b[i], x -> dico.size());
        return a.length == 0 ? 0 : (double) distance(ia, ib) / a.length;
    }

    private static int distance(int[] a, int[] b) {
        int[] prec = new int[b.length + 1], cour = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prec[j] = j;
        for (int i = 1; i <= a.length; i++) {
            cour[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int c = a[i - 1] == b[j - 1] ? 0 : 1;
                cour[j] = Math.min(Math.min(cour[j - 1] + 1, prec[j] + 1), prec[j - 1] + c);
            }
            int[] t = prec;
            prec = cour;
            cour = t;
        }
        return prec[b.length];
    }

    /**
     * NFKC (formes de présentation arabes et ligatures ramenées à leurs lettres),
     * sans tatweel, apostrophe typographique ramenée à l'apostrophe droite,
     * blancs réduits à une espace.
     */
    static String normaliser(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace("ـ", "")
                .replace('’', '\'')
                .replaceAll("\\s+", " ").trim();
    }
}
