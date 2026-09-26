package com.ipt.ged.ocr.moteur;

import com.ipt.ged.ocr.ExtracteurBureautique;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chaîne de traitement d'un job OCR (§4.3.4), <b>page par page</b> et
 * <b>sans plafond de pages</b> :
 * <ol>
 *   <li>le fichier arrive déchiffré, en mémoire (jamais sur disque) ;</li>
 *   <li>PDF : pour chaque page, la couche texte PDFBox est retenue si elle
 *       dépasse un seuil de caractères ; sinon la page est rendue à 300 dpi en
 *       niveaux de gris et reconnue par l'{@link OcrEngine} ;</li>
 *   <li>image : chaque page (TIFF multi-pages compris) est reconnue ;</li>
 *   <li>bureautique et texte : texte natif, sans OCR ;</li>
 *   <li>le texte est agrégé en une unité documentaire.</li>
 * </ol>
 * La mémoire reste bornée à une page rendue à la fois. Le PDF lui-même est tenu
 * en mémoire (jusqu'à 200 Mo, plafond de dépôt) et PDFBox est configuré pour ne
 * créer <b>aucun</b> fichier de travail.
 */
public class ExtracteurDocumentOcr {

    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/tiff", "image/bmp", "image/gif");
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String OLE2 = "application/x-tika-msoffice";
    private static final String SEPARATEUR_PAGES = "\n\n";

    private final OcrEngine moteur;
    private final ExtracteurBureautique bureautique;
    private final int dpi;
    private final int seuilCaracteresParPage;
    private final Duration delaiParPage;

    public ExtracteurDocumentOcr(OcrEngine moteur, ExtracteurBureautique bureautique, int dpi,
                                 int seuilCaracteresParPage, Duration delaiParPage) {
        this.moteur = moteur;
        this.bureautique = bureautique;
        this.dpi = dpi;
        this.seuilCaracteresParPage = seuilCaracteresParPage;
        this.delaiParPage = delaiParPage;
    }

    /** Appelé après chaque page : le worker y prolonge son bail sur le job. */
    @FunctionalInterface
    public interface SuiviPages {
        void pageTraitee(int page, int total);

        SuiviPages AUCUN = (p, t) -> { };
    }

    public TexteDocument extraire(InputStream contenu, String typeMime, String langue, SuiviPages suivi)
            throws EchecOcrException {
        String type = typeMime == null ? "" : typeMime.toLowerCase(Locale.ROOT);
        try {
            if (PDF.equals(type)) return pdf(contenu, langue, suivi);
            if (IMAGES.contains(type)) return images(contenu.readAllBytes(), langue, suivi);
            if (type.startsWith("text/")) return natif(texteBrut(contenu.readAllBytes()));
            if (DOCX.equals(type)) return natif(bureautique.lireFlux(contenu, "docx"));
            if (XLSX.equals(type)) return natif(bureautique.lireFlux(contenu, "xlsx"));
            if (PPTX.equals(type)) return natif(pptx(contenu));
            if ("application/msword".equals(type) || OLE2.equals(type)) return natif(bureautique.lireFlux(contenu, "doc"));
            if ("application/vnd.ms-excel".equals(type)) return natif(bureautique.lireFlux(contenu, "xls"));
            if (type.startsWith("application/vnd.oasis.opendocument.")) return natif(odf(contenu));
        } catch (EchecOcrException e) {
            throw e;
        } catch (Exception e) {
            throw new EchecOcrException(EchecOcrException.Motif.FICHIER_CORROMPU,
                    "lecture impossible (" + type + ") : " + e.getMessage(), e);
        }
        throw new EchecOcrException(EchecOcrException.Motif.FORMAT_NON_SUPPORTE, "format « " + typeMime + " »");
    }

    /* ---------- PDF ---------- */

    private TexteDocument pdf(InputStream contenu, String langue, SuiviPages suivi) throws Exception {
        PDDocument doc;
        try {
            doc = Loader.loadPDF(new RandomAccessReadBuffer(contenu), "", null, null,
                    IOUtils.createMemoryOnlyStreamCache());
        } catch (InvalidPasswordException e) {
            throw new EchecOcrException(EchecOcrException.Motif.PROTEGE_PAR_MOT_DE_PASSE, "PDF protégé par mot de passe", e);
        } catch (IOException e) {
            throw new EchecOcrException(EchecOcrException.Motif.FICHIER_CORROMPU, "PDF illisible : " + e.getMessage(), e);
        }
        try (doc) {
            int total = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer rendu = new PDFRenderer(doc);
            StringBuilder texte = new StringBuilder();
            int natives = 0, ocr = 0;
            for (int i = 0; i < total; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String page = stripper.getText(doc);
                if (page != null && caracteresUtiles(page) >= seuilCaracteresParPage) {
                    natives++;
                } else {
                    BufferedImage image = rendu.renderImageWithDPI(i, dpi, ImageType.GRAY);
                    page = moteur.reconnaitre(png(image), langue, delaiParPage);
                    ocr++;
                }
                ajouter(texte, page);
                suivi.pageTraitee(i + 1, total);
            }
            TexteDocument.Provenance p = ocr == 0 ? TexteDocument.Provenance.COUCHE_TEXTE
                    : natives == 0 ? TexteDocument.Provenance.OCR : TexteDocument.Provenance.MIXTE;
            return new TexteDocument(nettoyer(texte), total, natives, ocr, p);
        }
    }

    /** Caractères hors blancs : un en-tête vide ou quelques espaces ne font pas une couche texte. */
    private static int caracteresUtiles(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) n++;
        return n;
    }

    /* ---------- images ---------- */

    private TexteDocument images(byte[] octets, String langue, SuiviPages suivi) throws EchecOcrException, IOException {
        StringBuilder texte = new StringBuilder();
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(octets))) {
            Iterator<ImageReader> lecteurs = iis != null ? ImageIO.getImageReaders(iis) : null;
            if (lecteurs == null || !lecteurs.hasNext()) {
                // Format que Java ne découpe pas : le moteur le lit d'un bloc.
                ajouter(texte, moteur.reconnaitre(octets, langue, delaiParPage));
                suivi.pageTraitee(1, 1);
                return new TexteDocument(nettoyer(texte), 1, 0, 1, TexteDocument.Provenance.OCR);
            }
            ImageReader lecteur = lecteurs.next();
            try {
                lecteur.setInput(iis);
                int total = lecteur.getNumImages(true);
                if (total <= 1) {
                    ajouter(texte, moteur.reconnaitre(octets, langue, delaiParPage));
                    suivi.pageTraitee(1, 1);
                    return new TexteDocument(nettoyer(texte), 1, 0, 1, TexteDocument.Provenance.OCR);
                }
                for (int i = 0; i < total; i++) {
                    ajouter(texte, moteur.reconnaitre(png(lecteur.read(i)), langue, delaiParPage));
                    suivi.pageTraitee(i + 1, total);
                }
                return new TexteDocument(nettoyer(texte), total, 0, total, TexteDocument.Provenance.OCR);
            } finally {
                lecteur.dispose();
            }
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /* ---------- natifs ---------- */

    private static TexteDocument natif(String texte) {
        return new TexteDocument(nettoyer(new StringBuilder(texte == null ? "" : texte)), 1, 1, 0,
                TexteDocument.Provenance.NATIF);
    }

    /** UTF-8 strict, sinon Windows-1252 (fichiers texte historiques). */
    private static String texteBrut(byte[] octets) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(octets)).toString();
        } catch (CharacterCodingException e) {
            return new String(octets, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private static String pptx(InputStream in) throws IOException {
        try (XMLSlideShow s = new XMLSlideShow(in);
             SlideShowExtractor<?, ?> ex = new SlideShowExtractor<>(s)) {
            return ex.getText();
        }
    }

    /** Texte de {@code content.xml} d'un paquet OpenDocument, un paragraphe par ligne. */
    private static String odf(InputStream in) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("content.xml".equals(e.getName())) {
                    SAXParserFactory f = SAXParserFactory.newInstance();
                    f.setNamespaceAware(true);
                    // Pas d'entités externes : un document déposé ne doit pas
                    // faire lire un fichier du serveur (XXE).
                    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    StringBuilder sb = new StringBuilder();
                    f.newSAXParser().parse(new NonFermant(zip), new DefaultHandler() {
                        @Override public void characters(char[] ch, int start, int length) {
                            sb.append(ch, start, length);
                        }
                        @Override public void endElement(String uri, String local, String q) {
                            if ("p".equals(local) || "h".equals(local)) sb.append('\n');
                        }
                        @Override public void startElement(String uri, String local, String q, Attributes a) {
                            if ("tab".equals(local) || "s".equals(local)) sb.append(' ');
                        }
                    });
                    return sb.toString();
                }
            }
        }
        throw new EchecOcrException(EchecOcrException.Motif.FICHIER_CORROMPU, "paquet OpenDocument sans content.xml");
    }

    /* ---------- agrégation ---------- */

    private static void ajouter(StringBuilder texte, String page) {
        if (page == null) return;
        String p = page.strip();
        if (p.isEmpty()) return;
        if (texte.length() > 0) texte.append(SEPARATEUR_PAGES);
        texte.append(p);
    }

    /**
     * PostgreSQL refuse le caractère nul dans un {@code text}, que certains PDF
     * et l'OCR produisent ; les autres caractères de contrôle ne portent aucun
     * sens pour la recherche.
     */
    static String nettoyer(StringBuilder texte) {
        StringBuilder sb = new StringBuilder(texte.length());
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r' || c >= 0x20) sb.append(c == '\r' ? '\n' : c);
        }
        return sb.toString().strip();
    }

    /** Le parseur SAX ferme son flux : l'archive doit rester ouverte. */
    private static final class NonFermant extends java.io.FilterInputStream {
        NonFermant(InputStream in) { super(in); }
        @Override public void close() { }
    }
}
