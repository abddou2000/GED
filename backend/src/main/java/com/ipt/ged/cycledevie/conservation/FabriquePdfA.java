package com.ipt.ged.cycledevie.conservation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.type.BadFieldValueException;
import org.apache.xmpbox.xml.XmpSerializer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Iterator;
import java.util.UUID;

/**
 * Production de PDF/A-2B par PDFBox (§6.1.4 : « PDFBox pour les images et les
 * PDF ») : pages d'images, mise en conformité des métadonnées d'un PDF, et
 * rendu en images d'un PDF qui ne peut pas l'être autrement.
 *
 * <p>Ce qui fait un PDF/A ici : identification PDF/A (XMP {@code pdfaid:part=2},
 * {@code conformance=B}), intention de sortie sRGB (profil ICC embarqué),
 * dictionnaire d'information vidé (aucune divergence possible avec le XMP),
 * identifiant de fichier dans la remorque, images en RVB sans transparence.
 * Le résultat est ensuite <b>validé par veraPDF</b> : rien n'est présumé
 * conforme.
 */
public final class FabriquePdfA {

    /** Résolution du rendu d'un PDF en images, et des images sans résolution connue. */
    static final int DPI = 150;
    private static final String SRGB = "sRGB IEC61966-2.1";
    private static final String PRODUCTEUR = "GED Marchica Med";

    private FabriquePdfA() {
    }

    /** Une page par image (TIFF multipage compris) ; les JPEG restent en JPEG. */
    public static void depuisImages(Path image, String typeMime, String titre, Path sortie) throws IOException {
        boolean jpeg = "image/jpeg".equals(typeMime);
        try (PDDocument doc = new PDDocument();
             ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            if (in == null) throw new IOException("image illisible");
            Iterator<ImageReader> lecteurs = ImageIO.getImageReaders(in);
            if (!lecteurs.hasNext()) throw new IOException("format d'image non reconnu (" + typeMime + ")");
            ImageReader lecteur = lecteurs.next();
            try {
                lecteur.setInput(in, false, true);
                int n = lecteur.getNumImages(true);
                for (int i = 0; i < n; i++) {
                    ajouterPage(doc, rvb(lecteur.read(i)), jpeg);
                }
            } finally {
                lecteur.dispose();
            }
            if (doc.getNumberOfPages() == 0) throw new IOException("image sans page");
            finaliser(doc, titre);
            enregistrer(doc, sortie);
        }
    }

    /**
     * Conserve le contenu du PDF (texte, polices, vecteurs) et n'ajoute que ce
     * qui manque à l'identification PDF/A. Suffit pour un PDF « propre »
     * (polices incorporées, sans chiffrement ni JavaScript) ; sinon la
     * validation échoue et l'appelant passe au rendu en images.
     */
    public static void normaliser(Path pdf, String titre, Path sortie) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.isEncrypted()) throw new IOException("PDF chiffré");
            PDDocumentCatalog catalogue = doc.getDocumentCatalog();
            catalogue.getCOSObject().removeItem(COSName.getPDFName("OutputIntents"));
            catalogue.getCOSObject().removeItem(COSName.NAMES); // JavaScript et fichiers joints
            catalogue.getCOSObject().removeItem(COSName.OPEN_ACTION);
            catalogue.getCOSObject().removeItem(COSName.AA);
            finaliser(doc, titre);
            enregistrer(doc, sortie);
        }
    }

    /**
     * Rend chaque page en image (repli : le PDF résultant est essentiellement
     * une image, sans couche texte — revue client, question Q7).
     */
    public static void rasteriser(Path pdf, String titre, Path sortie) throws IOException {
        try (PDDocument source = Loader.loadPDF(pdf.toFile()); PDDocument doc = new PDDocument()) {
            PDFRenderer rendu = new PDFRenderer(source);
            for (int i = 0; i < source.getNumberOfPages(); i++) {
                BufferedImage image = rendu.renderImageWithDPI(i, DPI, ImageType.RGB);
                ajouterPage(doc, image, true);
            }
            if (doc.getNumberOfPages() == 0) throw new IOException("PDF sans page");
            finaliser(doc, titre);
            enregistrer(doc, sortie);
        }
    }

    private static void ajouterPage(PDDocument doc, BufferedImage image, boolean jpeg) throws IOException {
        PDImageXObject xobjet = jpeg ? JPEGFactory.createFromImage(doc, image, 0.92f)
                : LosslessFactory.createFromImage(doc, image);
        float largeur = image.getWidth() * 72f / DPI;
        float hauteur = image.getHeight() * 72f / DPI;
        PDPage page = new PDPage(new PDRectangle(largeur, hauteur));
        doc.addPage(page);
        try (PDPageContentStream flux = new PDPageContentStream(doc, page)) {
            flux.drawImage(xobjet, 0, 0, largeur, hauteur);
        }
    }

    /** RVB opaque : la transparence et les espaces CMJN n'ont pas leur place sous une intention sRGB. */
    static BufferedImage rvb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        BufferedImage rvb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rvb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rvb;
    }

    /** Identification PDF/A-2B, intention de sortie sRGB, identifiant de fichier. */
    static void finaliser(PDDocument doc, String titre) throws IOException {
        doc.setDocumentInformation(new PDDocumentInformation());
        PDDocumentCatalog catalogue = doc.getDocumentCatalog();

        XMPMetadata xmp = XMPMetadata.createXMPMetadata();
        try {
            PDFAIdentificationSchema pdfa = xmp.createAndAddPDFAIdentificationSchema();
            pdfa.setPart(2);
            pdfa.setConformance("B");
        } catch (BadFieldValueException e) {
            throw new IOException(e);
        }
        DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
        dc.setTitle(titre != null && !titre.isBlank() ? titre : "Copie de conservation");
        dc.addCreator(PRODUCTEUR);
        XMPBasicSchema base = xmp.createAndAddXMPBasicSchema();
        Calendar maintenant = Calendar.getInstance();
        base.setCreateDate(maintenant);
        base.setModifyDate(maintenant);
        base.setCreatorTool(PRODUCTEUR);
        AdobePDFSchema pdf = xmp.createAndAddAdobePDFSchema();
        pdf.setProducer(PRODUCTEUR);
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try {
            new XmpSerializer().serialize(xmp, octets, true);
        } catch (javax.xml.transform.TransformerException e) {
            throw new IOException(e);
        }
        PDMetadata metadonnees = new PDMetadata(doc);
        metadonnees.importXMPMetadata(octets.toByteArray());
        catalogue.setMetadata(metadonnees);

        PDOutputIntent intention = new PDOutputIntent(doc,
                new ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData()));
        intention.setInfo(SRGB);
        intention.setOutputCondition(SRGB);
        intention.setOutputConditionIdentifier(SRGB);
        intention.setRegistryName("http://www.color.org");
        catalogue.addOutputIntent(intention);

        doc.getDocument().setDocumentID(identifiant());
        if (doc.getVersion() < 1.4f) doc.setVersion(1.7f);
    }

    private static COSArray identifiant() {
        byte[] id;
        try {
            id = MessageDigest.getInstance("MD5").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        COSArray tableau = new COSArray();
        tableau.add(new COSString(id));
        tableau.add(new COSString(id));
        return tableau;
    }

    private static void enregistrer(PDDocument doc, Path sortie) throws IOException {
        try (OutputStream out = Files.newOutputStream(sortie)) {
            doc.save(out);
        }
    }
}
