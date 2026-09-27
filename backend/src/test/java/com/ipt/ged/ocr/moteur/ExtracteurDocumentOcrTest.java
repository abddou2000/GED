package com.ipt.ged.ocr.moteur;

import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.ocr.ExtracteurBureautique;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.3.4 — chaîne d'extraction page par page, avec un moteur OCR simulé :
 * couche texte au-delà du seuil, OCR sinon, aucun plafond de pages, texte
 * agrégé, motifs d'échec.
 */
class ExtracteurDocumentOcrTest {

    /** Moteur factice : compte les pages reçues et rend un texte repérable. */
    static final class MoteurFactice implements OcrEngine {
        final AtomicInteger pages = new AtomicInteger();
        final List<String> langues = Collections.synchronizedList(new ArrayList<>());
        @Override public String nom() { return "factice"; }
        @Override public boolean disponible() { return true; }
        @Override public Set<String> languesInstallees() { return Set.of("fra", "ara"); }
        @Override public String reconnaitre(byte[] image, String langue, Duration delai) {
            langues.add(langue);
            return "texte reconnu page " + pages.incrementAndGet();
        }
    }

    private final MoteurFactice moteur = new MoteurFactice();
    private final ExtracteurDocumentOcr extracteur =
            new ExtracteurDocumentOcr(moteur, new ExtracteurBureautique(), 72, 25, Duration.ofSeconds(60));

    private TexteDocument extraire(byte[] contenu, String type) throws Exception {
        return extracteur.extraire(new ByteArrayInputStream(contenu), type, "fra+ara", ExtracteurDocumentOcr.SuiviPages.AUCUN);
    }

    private static BufferedImage blanc() {
        return new BufferedImage(200, 280, BufferedImage.TYPE_BYTE_GRAY);
    }

    @Test
    @DisplayName("PDF mixte : couche texte retenue au-delà du seuil, OCR sur les pages scannées, texte agrégé")
    void pdfMixte() throws Exception {
        byte[] pdf = Scans.pdf(List.of(
                "Page native : bordereau de transmission du courrier numero 2026-117 au service juridique.",
                blanc(),
                "Page native : accuse de reception signe par le bureau d'ordre digital de Marchica Med."));
        List<String> suivi = new ArrayList<>();
        TexteDocument t = extracteur.extraire(new ByteArrayInputStream(pdf), FormatsReconnus.PDF, "fra+ara",
                (p, total) -> suivi.add(p + "/" + total));
        assertEquals(3, t.nbPages());
        assertEquals(2, t.pagesNatives());
        assertEquals(1, t.pagesOcr());
        assertEquals(TexteDocument.Provenance.MIXTE, t.provenance());
        assertEquals(List.of("1/3", "2/3", "3/3"), suivi);
        assertEquals(List.of("fra+ara"), moteur.langues);
        int a = t.texte().indexOf("bordereau"), b = t.texte().indexOf("texte reconnu page 1"), c = t.texte().indexOf("accuse");
        assertTrue(a >= 0 && a < b && b < c, "ordre des pages conservé : " + t.texte());
    }

    @Test
    @DisplayName("Seuil par page : une couche texte trop courte (en-tête isolé) passe à l'OCR")
    void seuil() throws Exception {
        TexteDocument t = extraire(Scans.pdf(List.of("p. 1")), FormatsReconnus.PDF);
        assertEquals(1, t.pagesOcr());
        assertEquals(TexteDocument.Provenance.OCR, t.provenance());
    }

    @Test
    @DisplayName("Aucun plafond de pages : un scan de 40 pages est entièrement reconnu")
    void aucunPlafond() throws Exception {
        List<Object> pages = new ArrayList<>();
        for (int i = 0; i < 40; i++) pages.add(blanc());
        TexteDocument t = extraire(Scans.pdf(pages), FormatsReconnus.PDF);
        assertEquals(40, t.nbPages());
        assertEquals(40, moteur.pages.get());
        assertTrue(t.texte().contains("texte reconnu page 40"));
    }

    @Test
    @DisplayName("TIFF multi-pages : une reconnaissance par page")
    void tiffMultiPages() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter w = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.prepareWriteSequence(null);
            for (int i = 0; i < 3; i++) w.writeToSequence(new IIOImage(blanc(), null, null), null);
            w.endWriteSequence();
        }
        TexteDocument t = extraire(out.toByteArray(), "image/tiff");
        assertEquals(3, t.nbPages());
        assertEquals(3, moteur.pages.get());
    }

    @Test
    @DisplayName("Image simple : une page")
    void image() throws Exception {
        TexteDocument t = extraire(Echantillons.image("png"), "image/png");
        assertEquals(1, t.nbPages());
        assertEquals("texte reconnu page 1", t.texte());
    }

    @Test
    @DisplayName("Formats natifs lus sans OCR : docx, xlsx, pptx, OpenDocument, texte")
    void natifs() throws Exception {
        assertTrue(extraire(Echantillons.docx(), FormatsReconnus.DOCX).texte().contains("Contrat de prestation"));
        assertTrue(extraire(Echantillons.xlsx(), FormatsReconnus.XLSX).texte().contains("42"));
        assertEquals(TexteDocument.Provenance.NATIF, extraire(Echantillons.pptx(), FormatsReconnus.PPTX).provenance());
        assertEquals(TexteDocument.Provenance.NATIF, extraire(Echantillons.odf(FormatsReconnus.ODT), FormatsReconnus.ODT).provenance());
        assertEquals("Note ; مذكرة", extraire("Note ; مذكرة".getBytes(StandardCharsets.UTF_8), "text/plain").texte());
        assertEquals("Réunion", extraire("Réunion".getBytes(java.nio.charset.Charset.forName("windows-1252")), "text/csv").texte());
        assertEquals(0, moteur.pages.get());
    }

    @Test
    @DisplayName("Caractère nul et caractères de contrôle retirés (PostgreSQL refuse \\u0000)")
    void nettoyage() throws Exception {
        assertEquals("ab\ncd", extraire("a\u0000b\r\ncd\u0007".getBytes(StandardCharsets.UTF_8), "text/plain")
                .texte().replace("\n\n", "\n"));
    }

    @Test
    @DisplayName("Motifs d'échec : PDF protégé, fichier corrompu, format non supporté")
    void echecs() throws Exception {
        byte[] protege;
        try (PDDocument d = Loader.loadPDF(Scans.pdf(List.of("confidentiel du document protege par mot de passe")));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StandardProtectionPolicy p = new StandardProtectionPolicy("proprietaire", "secret", new AccessPermission());
            p.setEncryptionKeyLength(128);
            d.protect(p);
            d.save(out);
            protege = out.toByteArray();
        }
        EchecOcrException e1 = assertThrows(EchecOcrException.class, () -> extraire(protege, FormatsReconnus.PDF));
        assertEquals(EchecOcrException.Motif.PROTEGE_PAR_MOT_DE_PASSE, e1.motif());
        assertTrue(e1.definitif());
        EchecOcrException e2 = assertThrows(EchecOcrException.class,
                () -> extraire("%PDF-1.4 tronqué".getBytes(StandardCharsets.US_ASCII), FormatsReconnus.PDF));
        assertEquals(EchecOcrException.Motif.FICHIER_CORROMPU, e2.motif());
        EchecOcrException e3 = assertThrows(EchecOcrException.class,
                () -> extraire(new byte[]{1, 2}, "application/zip"));
        assertEquals(EchecOcrException.Motif.FORMAT_NON_SUPPORTE, e3.motif());
    }

    @Test
    @DisplayName("Délai dépassé sur une page : échec transitoire (reprise programmée)")
    void delai() {
        OcrEngine lentDirect = new OcrEngine() {
            @Override public String nom() { return "lent"; }
            @Override public boolean disponible() { return true; }
            @Override public Set<String> languesInstallees() { return Set.of(); }
            @Override public String reconnaitre(byte[] image, String langue, Duration delai) throws EchecOcrException {
                throw new EchecOcrException(EchecOcrException.Motif.DELAI_DEPASSE, "page non reconnue en 60 s");
            }
        };
        ExtracteurDocumentOcr x = new ExtracteurDocumentOcr(lentDirect, new ExtracteurBureautique(), 72, 25, Duration.ofSeconds(60));
        EchecOcrException e = assertThrows(EchecOcrException.class, () -> x.extraire(
                new ByteArrayInputStream(Scans.pdf(List.of(blanc()))), FormatsReconnus.PDF, "fra", ExtracteurDocumentOcr.SuiviPages.AUCUN));
        assertEquals(EchecOcrException.Motif.DELAI_DEPASSE, e.motif());
        assertFalse(e.definitif());
    }

    @Test
    @DisplayName("Langues : fra+ara par défaut, réglage par type, valeurs hors dossier refusées au démarrage")
    void langues() {
        LanguesOcr l = new LanguesOcr("fra+ara", java.util.Map.of("TD-FACT", "fra", "TD-ARABE", "ara"));
        assertEquals("fra+ara", l.pour(null));
        assertEquals("fra+ara", l.pour("TD-CONTRAT"));
        assertEquals("fra", l.pour("td-fact"));
        assertEquals("ara", l.pour("TD-ARABE"));
        assertThrows(IllegalStateException.class, () -> new LanguesOcr("fra+eng", java.util.Map.of()));
        assertThrows(IllegalStateException.class, () -> new LanguesOcr("fra", java.util.Map.of("X", "fra;rm -rf")));
    }
}
