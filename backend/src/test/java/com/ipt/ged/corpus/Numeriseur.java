package com.ipt.ged.corpus;

import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Simule le passage au scanner : fond et éclairage irréguliers, flou optique,
 * bruit, poussières, inclinaison, perforations, pli, bords sombres, puis
 * réduction en niveaux de gris ou en noir et blanc et compression.
 */
final class Numeriseur {

    private Numeriseur() {
    }

    enum Qualite { BONNE, MOYENNE, MAUVAISE }

    enum Mode { COULEUR, GRIS, NOIR_BLANC }

    record Reglages(Qualite qualite, int dpi, Mode mode, String format, float jpeg) {
    }

    /** Image numérisée et transformation des coordonnées de page (points) vers ses pixels. */
    record Scan(BufferedImage image, double inclinaisonDeg, AffineTransform pointsVersPixels) {
    }

    static Reglages tirer(Qualite q, Random r) {
        int dpi = switch (q) {
            case BONNE -> r.nextDouble() < 0.8 ? 300 : 200;
            case MOYENNE -> r.nextDouble() < 0.5 ? 300 : 200;
            case MAUVAISE -> r.nextDouble() < 0.7 ? 200 : 150;
        };
        double u = r.nextDouble();
        Mode m = switch (q) {
            case BONNE -> u < 0.3 ? Mode.COULEUR : u < 0.9 ? Mode.GRIS : Mode.NOIR_BLANC;
            case MOYENNE -> u < 0.2 ? Mode.COULEUR : u < 0.75 ? Mode.GRIS : Mode.NOIR_BLANC;
            case MAUVAISE -> u < 0.1 ? Mode.COULEUR : u < 0.55 ? Mode.GRIS : Mode.NOIR_BLANC;
        };
        String f = m == Mode.NOIR_BLANC ? (r.nextDouble() < 0.6 ? "tif" : "png") : "jpg";
        float jpeg = switch (q) {
            case BONNE -> 0.85f + r.nextFloat() * 0.07f;
            case MOYENNE -> 0.70f + r.nextFloat() * 0.15f;
            case MAUVAISE -> 0.50f + r.nextFloat() * 0.20f;
        };
        return new Reglages(q, dpi, m, f, jpeg);
    }

    static Scan numeriser(Page p, Reglages rg, Random r) {
        BufferedImage img = p.image;
        int w = img.getWidth();
        int h = img.getHeight();
        int qi = rg.qualite().ordinal();
        double k = p.echelle;

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (r.nextDouble() < new double[]{0.05, 0.15, 0.25}[qi]) {
            int dia = (int) (6 / 25.4 * rg.dpi());
            int x = (int) (12 / 25.4 * rg.dpi());
            int ec = (int) (40 / 25.4 * rg.dpi());
            g.setColor(new Color(70 + r.nextInt(60), 70 + r.nextInt(60), 70 + r.nextInt(60)));
            g.fill(new Ellipse2D.Double(x, h / 2.0 - ec - dia / 2.0, dia, dia));
            g.fill(new Ellipse2D.Double(x, h / 2.0 + ec - dia / 2.0, dia, dia));
        }
        if (r.nextDouble() < new double[]{0.05, 0.15, 0.3}[qi]) {
            int yp = (int) (h * (0.3 + r.nextDouble() * 0.1));
            int ep = (int) (k * 6);
            g.setPaint(new GradientPaint(0, yp - ep, new Color(0, 0, 0, 0), 0, yp, new Color(0, 0, 0, 40)));
            g.fill(new Rectangle2D.Double(0, yp - ep, w, ep));
            g.setPaint(new GradientPaint(0, yp, new Color(255, 255, 255, 60), 0, yp + ep, new Color(255, 255, 255, 0)));
            g.fill(new Rectangle2D.Double(0, yp, w, ep));
        }
        g.dispose();

        bruiter(img, qi, r);

        if (r.nextDouble() < new double[]{0.4, 0.8, 1.0}[qi]) {
            float c = qi == 2 ? 0.30f : 0.45f;
            float b = (1 - c) / 8;
            float[] noyau = {b, b, b, b, c, b, b, b, b};
            img = new ConvolveOp(new Kernel(3, 3, noyau), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
        }

        double ang = r.nextGaussian() * new double[]{0.3, 0.9, 1.8}[qi];
        double lim = new double[]{0.8, 2.5, 4}[qi];
        ang = Math.max(-lim, Math.min(lim, ang));
        double dx = r.nextGaussian() * w * 0.004;
        double dy = r.nextGaussian() * h * 0.004;
        boolean bordsSombres = r.nextDouble() < new double[]{0.05, 0.3, 0.5}[qi];
        AffineTransform rot = new AffineTransform();
        rot.translate(dx, dy);
        rot.rotate(Math.toRadians(ang), w / 2.0, h / 2.0);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D o = out.createGraphics();
        int fond = bordsSombres ? 25 + r.nextInt(40) : 236 + r.nextInt(16);
        o.setColor(new Color(fond, fond, fond));
        o.fillRect(0, 0, w, h);
        o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        o.drawImage(img, rot, null);
        if (bordsSombres) {
            int ep = (int) (k * (2 + r.nextDouble() * 8));
            o.setColor(new Color(fond, fond, fond));
            switch (r.nextInt(3)) {
                case 0 -> o.fillRect(0, 0, ep, h);
                case 1 -> o.fillRect(w - ep, 0, ep, h);
                default -> o.fillRect(0, h - ep, w, ep);
            }
        }
        o.dispose();

        if (rg.mode() != Mode.NOIR_BLANC && r.nextDouble() < new double[]{0.9, 0.6, 0.2}[qi]) {
            niveaux(out, 8 + r.nextInt(20), 212 + r.nextInt(28));
        }
        BufferedImage fin = switch (rg.mode()) {
            case COULEUR -> out;
            case GRIS -> gris(out);
            case NOIR_BLANC -> noirBlanc(out, qi, r);
        };
        AffineTransform t = new AffineTransform(rot);
        t.scale(k, k);
        return new Scan(fin, ang, t);
    }

    private static void bruiter(BufferedImage img, int qi, Random r) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        double amp = new double[]{0.03, 0.07, 0.12}[qi] * (0.5 + r.nextDouble());
        double sigma = new double[]{3, 7, 12}[qi] * (0.7 + r.nextDouble() * 0.6);
        double[] gx = new double[w];
        double[] gy = new double[h];
        double ax = r.nextDouble() * Math.PI * 2;
        double ay = r.nextDouble() * Math.PI * 2;
        for (int x = 0; x < w; x++) {
            gx[x] = 0.5 + 0.5 * Math.sin(ax + x * Math.PI / w);
        }
        for (int y = 0; y < h; y++) {
            gy[y] = 0.5 + 0.5 * Math.sin(ay + y * 1.3 * Math.PI / h);
        }
        long s = r.nextLong() | 1;
        double kb = sigma / 128.0;
        for (int y = 0; y < h; y++) {
            int ligne = y * w;
            for (int x = 0; x < w; x++) {
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                double n = ((s & 0xFF) + ((s >>> 8) & 0xFF) - 255) * kb;
                double f = 1 - amp * (gx[x] * 0.6 + gy[y] * 0.4);
                int v = px[ligne + x];
                int rr = c((((v >> 16) & 0xFF) * f) + n);
                int gg = c((((v >> 8) & 0xFF) * f) + n);
                int bb = c(((v & 0xFF) * f) + n);
                px[ligne + x] = (rr << 16) | (gg << 8) | bb;
            }
        }
        int poussieres = (int) (new double[]{40, 400, 2000}[qi] * (0.3 + r.nextDouble()));
        double k = w / Page.LARGEUR;
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < poussieres; i++) {
            int v = r.nextInt(90);
            g.setColor(new Color(v, v, v));
            double d = (0.25 + r.nextDouble() * r.nextDouble() * 1.2) * k;
            g.fill(new Ellipse2D.Double(r.nextDouble() * w, r.nextDouble() * h, d, d * (0.5 + r.nextDouble())));
        }
        g.dispose();
    }

    /** Correction de niveaux du pilote de scanner : le fond du papier passe au blanc, le noir est renforcé. */
    private static void niveaux(BufferedImage img, int noir, int blanc) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = c((i - noir) * 255.0 / (blanc - noir));
        }
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            px[i] = (lut[(v >> 16) & 0xFF] << 16) | (lut[(v >> 8) & 0xFF] << 8) | lut[v & 0xFF];
        }
    }

    private static int c(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    private static BufferedImage gris(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        int[] px = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            int v = px[i];
            dst[i] = (byte) ((((v >> 16) & 0xFF) * 299 + ((v >> 8) & 0xFF) * 587 + (v & 0xFF) * 114) / 1000);
        }
        return out;
    }

    private static BufferedImage noirBlanc(BufferedImage src, int qi, Random r) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster ras = out.getRaster();
        int[] px = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int seuil = (int) (165 + r.nextGaussian() * new double[]{5, 9, 14}[qi]);
        int[] ligne = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = px[y * w + x];
                int l = (((v >> 16) & 0xFF) * 299 + ((v >> 8) & 0xFF) * 587 + (v & 0xFF) * 114) / 1000;
                ligne[x] = l >= seuil ? 1 : 0;
            }
            ras.setSamples(0, y, w, 1, 0, ligne);
        }
        return out;
    }

    // ---------------------------------------------------------------- écriture

    /** Écrit l'image avec sa résolution (JFIF, pHYs ou tags TIFF), indispensable à Tesseract. */
    static void ecrire(BufferedImage img, Path fichier, String format, int dpi, float qualiteJpeg) throws IOException {
        String nom = format.equals("jpg") ? "jpeg" : format.equals("tif") ? "tiff" : format;
        ImageWriter w = ImageIO.getImageWritersByFormatName(nom).next();
        ImageWriteParam p = w.getDefaultWriteParam();
        if (nom.equals("jpeg")) {
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(qualiteJpeg);
        } else if (nom.equals("tiff")) {
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionType(img.getType() == BufferedImage.TYPE_BYTE_BINARY ? "CCITT T.6" : "LZW");
        }
        IIOMetadata md = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), p);
        if (nom.equals("jpeg")) {
            IIOMetadataNode racine = (IIOMetadataNode) md.getAsTree("javax_imageio_jpeg_image_1.0");
            IIOMetadataNode jfif = (IIOMetadataNode) trouver(racine, "app0JFIF");
            if (jfif != null) {
                jfif.setAttribute("resUnits", "1");
                jfif.setAttribute("Xdensity", String.valueOf(dpi));
                jfif.setAttribute("Ydensity", String.valueOf(dpi));
                md.setFromTree("javax_imageio_jpeg_image_1.0", racine);
            }
        } else if (nom.equals("png")) {
            // Le greffon PNG du JDK convertit mal la taille de pixel standard : pHYs est posé directement.
            IIOMetadataNode phys = new IIOMetadataNode("pHYs");
            String ppm = String.valueOf(Math.round(dpi / 0.0254));
            phys.setAttribute("pixelsPerUnitXAxis", ppm);
            phys.setAttribute("pixelsPerUnitYAxis", ppm);
            phys.setAttribute("unitSpecifier", "meter");
            IIOMetadataNode racine = new IIOMetadataNode("javax_imageio_png_1.0");
            racine.appendChild(phys);
            md.mergeTree("javax_imageio_png_1.0", racine);
        } else {
            double mm = 25.4 / dpi;
            IIOMetadataNode h = new IIOMetadataNode("HorizontalPixelSize");
            h.setAttribute("value", String.valueOf(mm));
            IIOMetadataNode v = new IIOMetadataNode("VerticalPixelSize");
            v.setAttribute("value", String.valueOf(mm));
            IIOMetadataNode dim = new IIOMetadataNode("Dimension");
            dim.appendChild(h);
            dim.appendChild(v);
            IIOMetadataNode racine = new IIOMetadataNode("javax_imageio_1.0");
            racine.appendChild(dim);
            md.mergeTree("javax_imageio_1.0", racine);
        }
        Files.deleteIfExists(fichier);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(fichier.toFile())) {
            w.setOutput(out);
            w.write(null, new IIOImage(img, null, md), p);
        } finally {
            w.dispose();
        }
    }

    private static Node trouver(Node n, String nom) {
        if (nom.equals(n.getNodeName())) {
            return n;
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            Node t = trouver(c, nom);
            if (t != null) {
                return t;
            }
        }
        return null;
    }
}
