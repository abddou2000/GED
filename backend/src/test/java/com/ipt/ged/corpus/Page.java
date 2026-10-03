package com.ipt.ged.corpus;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Une page A4 en cours de rédaction, dessinée en points typographiques (1/72 de
 * pouce) sur une image à la résolution de numérisation.
 *
 * <p>Chaque élément porteur de texte est consigné dans {@link #zones} avec son
 * emprise et son texte exact : c'est la vérité terrain. Signatures, logos et
 * transparence du verso ne portent pas de texte et ne sont pas consignés.
 */
final class Page {

    static final double LARGEUR = 595.276;
    static final double HAUTEUR = 841.89;

    enum Align { GAUCHE, CENTRE, DROITE }

    /** Emprise en points (repère de la page non inclinée) et texte exact, ligne par ligne. */
    record Zone(String type, double x, double y, double l, double h, List<String> lignes) {
    }

    final int dpi;
    final double echelle;
    final BufferedImage image;
    final Graphics2D g;
    final Random r;
    final Polices polices;
    final double marge;
    double y;
    final List<Zone> zones = new ArrayList<>();

    final Font corps;
    final Font gras;
    final Font petit;
    final Font titre;
    final Color encre;

    final Font plume;
    final float taillePlume;
    final Color encreStylo;

    Page(int dpi, Random r, Polices polices) {
        this.dpi = dpi;
        this.r = r;
        this.polices = polices;
        this.echelle = dpi / 72.0;
        int w = (int) Math.round(LARGEUR / 72 * dpi);
        int h = (int) Math.round(HAUTEUR / 72 * dpi);
        image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        g = image.createGraphics();
        int t = 244 + r.nextInt(12);
        g.setColor(new Color(t, t, Math.max(0, t - r.nextInt(8))));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.scale(echelle, echelle);

        marge = 46 + r.nextDouble() * 26;
        y = marge * 0.75;

        Font base = Donnees.chance(r, 0.08) ? polices.machine : Donnees.un(r, polices.imprimees.toArray(new Font[0]));
        float taille = 9.5f + r.nextFloat() * 2.5f;
        corps = base.deriveFont(Font.PLAIN, taille);
        gras = base.deriveFont(Font.BOLD, taille);
        petit = base.deriveFont(Font.PLAIN, taille - 1.8f);
        titre = base.deriveFont(Font.BOLD, taille + 3 + r.nextInt(5));
        int k = 10 + r.nextInt(35);
        encre = new Color(k, k, k + r.nextInt(10));

        plume = Donnees.un(r, polices.manuscrites.toArray(new Font[0]));
        taillePlume = 13f + r.nextFloat() * 6f;
        encreStylo = Donnees.chance(r, 0.65)
                ? new Color(15 + r.nextInt(30), 30 + r.nextInt(40), 120 + r.nextInt(80))
                : new Color(15 + r.nextInt(25), 15 + r.nextInt(25), 25 + r.nextInt(25));
    }

    // ------------------------------------------------------------ géométrie

    double largeur() {
        return LARGEUR - 2 * marge;
    }

    /** Interligne imposé à l'écriture manuscrite (papier réglé) ; 0 = libre. */
    double interligneImpose;

    /** Hauteur à garder libre en bas de page (bloc de signature à venir). */
    double reserve;

    double bas() {
        return HAUTEUR - marge * 0.9 - reserve;
    }

    boolean place(double h) {
        return y + h <= bas();
    }

    void espace(double pt) {
        y += pt;
    }

    FontRenderContext frc() {
        return g.getFontRenderContext();
    }

    double larg(String s, Font f) {
        return f.getStringBounds(s, frc()).getWidth();
    }

    static double interligne(Font f) {
        return f.getSize2D() * 1.3;
    }

    List<String> couper(String s, Font f, double max) {
        List<String> lignes = new ArrayList<>();
        StringBuilder cour = new StringBuilder();
        for (String mot : s.split(" ")) {
            if (mot.isEmpty()) {
                continue;
            }
            String essai = cour.isEmpty() ? mot : cour + " " + mot;
            if (larg(essai, f) <= max || cour.isEmpty()) {
                cour.setLength(0);
                cour.append(essai);
            } else {
                lignes.add(cour.toString());
                cour.setLength(0);
                cour.append(mot);
            }
        }
        if (!cour.isEmpty()) {
            lignes.add(cour.toString());
        }
        return lignes;
    }

    // ------------------------------------------------------- texte imprimé

    /** Texte imprimé avec retour à la ligne ; s'arrête en bas de page. Renvoie le nombre de lignes posées. */
    int texte(String s, Font f, Align a, double x, double largeur) {
        List<String> lignes = couper(s, f, largeur);
        double il = interligne(f);
        double y0 = y;
        List<String> posees = new ArrayList<>();
        g.setFont(f);
        g.setColor(encre);
        double maxL = 0;
        for (String l : lignes) {
            if (!place(il)) {
                break;
            }
            double lw = larg(l, f);
            double lx = switch (a) {
                case GAUCHE -> x;
                case CENTRE -> x + (largeur - lw) / 2;
                case DROITE -> x + largeur - lw;
            };
            g.drawString(l, (float) lx, (float) (y + f.getSize2D()));
            posees.add(l);
            maxL = Math.max(maxL, lw);
            y += il;
        }
        if (!posees.isEmpty()) {
            double zx = a == Align.GAUCHE ? x : a == Align.CENTRE ? x + (largeur - maxL) / 2 : x + largeur - maxL;
            zones.add(new Zone("imprime", zx, y0, maxL, y - y0, posees));
        }
        return posees.size();
    }

    int texte(String s, Font f, Align a) {
        return texte(s, f, a, marge, largeur());
    }

    int paragraphe(String s) {
        int n = texte(s, corps, Align.GAUCHE);
        y += corps.getSize2D() * 0.7;
        return n;
    }

    /** Deux blocs côte à côte (en-tête gauche / droite) ; le curseur descend sous le plus long. */
    void colonnes(List<String> gauche, List<String> droite, Font f) {
        double y0 = y;
        for (String l : gauche) {
            texte(l, f, Align.GAUCHE, marge, largeur() * 0.55);
        }
        double yg = y;
        y = y0;
        for (String l : droite) {
            texte(l, f, Align.DROITE, marge + largeur() * 0.45, largeur() * 0.55);
        }
        y = Math.max(y, yg);
    }

    void filet(double epaisseur) {
        g.setColor(encre);
        g.setStroke(new BasicStroke((float) epaisseur));
        g.draw(new java.awt.geom.Line2D.Double(marge, y, LARGEUR - marge, y));
        y += 4;
    }

    // -------------------------------------------------------------- tableau

    /**
     * Tableau quadrillé. Les colonnes listées dans {@code manuscrites} sont remplies
     * à la main ; {@code signatures} est une colonne d'émargement (paraphes, sans texte).
     * La vérité terrain met une ligne par rangée, cellules séparées par une tabulation.
     */
    void tableau(String[] entetes, List<String[]> rangees, double[] poids, java.util.Set<Integer> manuscrites,
                 int signatures) {
        double total = 0;
        for (double p : poids) {
            total += p;
        }
        double[] lc = new double[poids.length];
        for (int i = 0; i < poids.length; i++) {
            lc[i] = largeur() * poids[i] / total;
        }
        Font fe = gras.deriveFont(gras.getSize2D() - 0.5f);
        Font fc = corps.deriveFont(corps.getSize2D() - 0.5f);
        double pad = 3;
        boolean fond = Donnees.chance(r, 0.5);
        float trait = 0.4f + r.nextFloat() * 0.6f;
        double y0 = y;
        List<String> verite = new ArrayList<>();
        List<String[]> toutes = new ArrayList<>();
        toutes.add(entetes);
        toutes.addAll(rangees);
        for (int ri = 0; ri < toutes.size(); ri++) {
            String[] rg = toutes.get(ri);
            boolean entete = ri == 0;
            Font f = entete ? fe : fc;
            List<List<String>> cell = new ArrayList<>();
            int nl = 1;
            for (int c = 0; c < rg.length; c++) {
                boolean main = !entete && manuscrites.contains(c);
                Font fm = main ? plume.deriveFont(taillePlume * 0.85f) : f;
                List<String> cl = rg[c].isEmpty() ? List.of() : couper(rg[c], fm, lc[c] - 2 * pad);
                cell.add(cl);
                nl = Math.max(nl, cl.size());
            }
            double hr = nl * interligne(f) + 2 * pad + (signatures >= 0 && !entete ? 10 : 0);
            if (!manuscrites.isEmpty() && !entete) {
                hr = Math.max(hr, nl * taillePlume * 1.05 + 2 * pad);
            }
            if (!place(hr)) {
                break;
            }
            if (entete && fond) {
                g.setColor(new Color(225, 225, 225));
                g.fill(new Rectangle2D.Double(marge, y, largeur(), hr));
            }
            double x = marge;
            for (int c = 0; c < rg.length; c++) {
                boolean main = !entete && manuscrites.contains(c);
                if (main) {
                    double yy = y + pad;
                    for (String l : cell.get(c)) {
                        ecrireMain(l, x + pad, yy, taillePlume * 0.85f);
                        yy += taillePlume * 1.0;
                    }
                } else if (!entete && c == signatures) {
                    signature(x + 6, y + 3, lc[c] - 12, hr - 6);
                } else {
                    g.setFont(f);
                    g.setColor(encre);
                    double yy = y + pad;
                    boolean nombre = !entete && rg[c].matches("[0-9 ,.%]+");
                    for (String l : cell.get(c)) {
                        double lx = nombre ? x + lc[c] - pad - larg(l, f) : x + pad;
                        g.drawString(l, (float) lx, (float) (yy + f.getSize2D()));
                        yy += interligne(f);
                    }
                }
                x += lc[c];
            }
            g.setColor(encre);
            g.setStroke(new BasicStroke(trait));
            g.draw(new Rectangle2D.Double(marge, y, largeur(), hr));
            x = marge;
            for (int c = 0; c < rg.length - 1; c++) {
                x += lc[c];
                g.draw(new java.awt.geom.Line2D.Double(x, y, x, y + hr));
            }
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < rg.length; c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                sb.append(c == signatures && !entete ? "" : String.join(" ", cell.get(c)));
            }
            verite.add(sb.toString());
            y += hr;
        }
        if (!verite.isEmpty()) {
            zones.add(new Zone(manuscrites.isEmpty() ? "tableau" : "tableau_manuscrit", marge, y0, largeur(),
                    y - y0, verite));
        }
        y += 8;
    }

    // ----------------------------------------------------------- manuscrit

    /** Écrit à la main un texte, avec retour à la ligne ; consigne une zone « manuscrit ». Renvoie la hauteur. */
    double manuscrit(String s, double x, double y0, double largeur, float taille) {
        Font f = plume.deriveFont(taille);
        List<String> lignes = couper(s, f, largeur * 0.92);
        double yy = y0;
        double il = interligneImpose > 0 ? interligneImpose : taille * (1.15 + r.nextDouble() * 0.2);
        List<String> posees = new ArrayList<>();
        double maxL = 0;
        for (String l : lignes) {
            if (yy + il > bas()) {
                break;
            }
            double lw = ecrireMain(l, x + r.nextDouble() * 4, yy, taille);
            maxL = Math.max(maxL, lw);
            posees.add(l);
            yy += il;
        }
        if (!posees.isEmpty()) {
            zones.add(new Zone("manuscrit", x, y0, maxL + 4, yy - y0, posees));
        }
        return yy - y0;
    }

    /** Manuscrit au fil du texte, sous le curseur. */
    void manuscritFlux(String s) {
        double h = manuscrit(s, marge + 6, y, largeur() - 12, taillePlume);
        y += h + 4;
    }

    /** Dessine une ligne manuscrite glyphe par glyphe (ligne de base ondulée, inclinaison et taille variables). */
    double ecrireMain(String l, double x0, double y0, float taille) {
        Font f = plume.deriveFont(taille);
        double pente = Math.toRadians(r.nextGaussian() * 0.9);
        double phase = r.nextDouble() * Math.PI * 2;
        double amp = taille * 0.04;
        double derive = 0;
        double x = x0;
        double base = y0 + taille * 0.85;
        int rouge = encreStylo.getRed();
        int vert = encreStylo.getGreen();
        int bleu = encreStylo.getBlue();
        AffineTransform sauve = g.getTransform();
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == ' ') {
                x += larg(" ", f) * (0.9 + r.nextDouble() * 0.6);
                derive *= 0.5;
                continue;
            }
            derive += r.nextGaussian() * taille * 0.012;
            double dy = amp * Math.sin(phase + x * 0.05) + derive + (x - x0) * Math.tan(pente);
            double sx = 1 + r.nextGaussian() * 0.05;
            double sy = 1 + r.nextGaussian() * 0.06;
            g.translate(x, base + dy);
            g.rotate(Math.toRadians(r.nextGaussian() * 2.5));
            g.scale(sx, sy);
            int v = r.nextInt(25) - 12;
            g.setColor(new Color(clamp(rouge + v), clamp(vert + v), clamp(bleu + v)));
            g.setFont(f);
            g.drawString(String.valueOf(c), 0f, 0f);
            g.setTransform(sauve);
            x += larg(String.valueOf(c), f) * sx * (0.95 + r.nextDouble() * 0.1);
        }
        return x - x0;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------- signature, logo, verso

    void signature(double x, double y0, double l, double h) {
        Path2D.Double p = new Path2D.Double();
        double cx = x + l * 0.1;
        double cy = y0 + h * (0.4 + r.nextDouble() * 0.3);
        p.moveTo(cx, cy);
        int n = 3 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            double nx = x + l * (0.15 + 0.75 * (i + 1) / n) + r.nextGaussian() * 4;
            double ny = y0 + h * (0.2 + r.nextDouble() * 0.6);
            p.curveTo(cx + r.nextGaussian() * l * 0.2, y0 + r.nextDouble() * h,
                    nx + r.nextGaussian() * l * 0.15, y0 + h - r.nextDouble() * h, nx, ny);
            cx = nx;
            cy = ny;
        }
        p.curveTo(cx + 10, cy + 6, x + l * 0.3, y0 + h * 0.95, x + l * 0.05, y0 + h * 0.85);
        g.setColor(encreStylo);
        g.setStroke(new BasicStroke((float) (0.9 + r.nextDouble() * 0.9), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    void logo(double x, double y0, double t) {
        Color c = new Color(r.nextInt(140), r.nextInt(140), 60 + r.nextInt(150));
        g.setColor(c);
        switch (r.nextInt(3)) {
            case 0 -> {
                g.fill(new Ellipse2D.Double(x, y0, t, t));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(x + t * 0.25, y0 + t * 0.25, t * 0.5, t * 0.5));
            }
            case 1 -> {
                g.fill(new RoundRectangle2D.Double(x, y0, t, t, t * 0.3, t * 0.3));
                g.setColor(Color.WHITE);
                g.fill(new Rectangle2D.Double(x + t * 0.2, y0 + t * 0.45, t * 0.6, t * 0.12));
            }
            default -> {
                Path2D.Double tri = new Path2D.Double();
                tri.moveTo(x + t / 2, y0);
                tri.lineTo(x + t, y0 + t);
                tri.lineTo(x, y0 + t);
                tri.closePath();
                g.fill(tri);
            }
        }
    }

    /** Texte du verso vu par transparence : très pâle, en miroir, sans vérité terrain. */
    void transparenceVerso(String texte) {
        AffineTransform sauve = g.getTransform();
        g.translate(LARGEUR, 0);
        g.scale(-1, 1);
        int v = 228 + r.nextInt(14);
        g.setColor(new Color(v, v, v));
        g.setFont(corps);
        double yy = marge + r.nextDouble() * 60;
        for (String l : couper(texte, corps, largeur())) {
            if (yy > HAUTEUR - marge) {
                break;
            }
            g.drawString(l, (float) marge, (float) yy);
            yy += interligne(corps);
        }
        g.setTransform(sauve);
    }

    // --------------------------------------------------------------- tampons

    enum Forme { ROND, RECTANGLE, MOT }

    /**
     * Appose un tampon encré (texture irrégulière, rotation, encre partiellement
     * transparente). Pour un tampon rond, la première ligne court sur la couronne.
     */
    void tampon(Forme forme, List<String> lignes, double cx, double cy, Color couleur, double angleDeg) {
        Font ft = new Font(Font.SANS_SERIF, Font.BOLD, 8);
        double lw;
        double lh;
        if (forme == Forme.ROND) {
            lw = lh = 92 + r.nextDouble() * 20;
        } else {
            Font fm = forme == Forme.MOT ? ft.deriveFont(18f) : ft.deriveFont(9f);
            double m = 0;
            for (String s : lignes) {
                m = Math.max(m, larg(s, fm));
            }
            lw = m + 20;
            lh = lignes.size() * interligne(fm) + 14;
        }
        int bw = (int) Math.ceil(lw * echelle) + 4;
        int bh = (int) Math.ceil(lh * echelle) + 4;
        BufferedImage buf = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D t = buf.createGraphics();
        t.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        t.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        t.scale(echelle, echelle);
        t.translate(2 / echelle, 2 / echelle);
        t.setColor(couleur);
        switch (forme) {
            case ROND -> dessinerRond(t, lignes, lw);
            case RECTANGLE -> {
                Font f = ft.deriveFont(9f);
                t.setStroke(new BasicStroke(1.6f));
                t.draw(new Rectangle2D.Double(1, 1, lw - 2, lh - 2));
                t.setStroke(new BasicStroke(0.7f));
                t.draw(new Rectangle2D.Double(4, 4, lw - 8, lh - 8));
                t.setFont(f);
                double yy = 7;
                for (String s : lignes) {
                    t.drawString(s, (float) ((lw - larg(s, f)) / 2), (float) (yy + f.getSize2D()));
                    yy += interligne(f);
                }
            }
            case MOT -> {
                Font f = ft.deriveFont(18f);
                t.setStroke(new BasicStroke(2.2f));
                t.draw(new RoundRectangle2D.Double(1.5, 1.5, lw - 3, lh - 3, 8, 8));
                t.setFont(f);
                t.drawString(lignes.get(0), 10f, (float) (7 + f.getSize2D()));
            }
        }
        t.dispose();
        texturer(buf);

        double ang = Math.toRadians(angleDeg);
        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.rotate(ang);
        at.scale(1 / echelle, 1 / echelle);
        at.translate(-bw / 2.0, -bh / 2.0);
        java.awt.Composite c0 = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f + r.nextFloat() * 0.25f));
        g.drawImage(buf, at, null);
        g.setComposite(c0);

        double cos = Math.abs(Math.cos(ang));
        double sin = Math.abs(Math.sin(ang));
        double ew = lw * cos + lh * sin;
        double eh = lw * sin + lh * cos;
        zones.add(new Zone("tampon", cx - ew / 2, cy - eh / 2, ew, eh, lignes.stream().map(String::trim).toList()));
    }

    private void dessinerRond(Graphics2D t, List<String> lignes, double d) {
        double rr = d / 2;
        t.setStroke(new BasicStroke(1.8f));
        t.draw(new Ellipse2D.Double(1, 1, d - 2, d - 2));
        t.setStroke(new BasicStroke(0.8f));
        double ri = rr - 15;
        t.draw(new Ellipse2D.Double(rr - ri, rr - ri, 2 * ri, 2 * ri));
        Font fa = new Font(Font.SANS_SERIF, Font.BOLD, 7);
        String arc = lignes.get(0);
        double rayonTexte = rr - 11;
        double span = Math.min(Math.PI * 1.75, arc.length() * 0.11);
        double a0 = -Math.PI / 2 - span / 2;
        t.setFont(fa);
        for (int i = 0; i < arc.length(); i++) {
            double a = a0 + span * (i + 0.5) / arc.length();
            AffineTransform s = t.getTransform();
            t.translate(rr + rayonTexte * Math.cos(a), rr + rayonTexte * Math.sin(a));
            t.rotate(a + Math.PI / 2);
            String ch = String.valueOf(arc.charAt(i));
            t.drawString(ch, (float) (-larg(ch, fa) / 2), 0f);
            t.setTransform(s);
        }
        Font fc = new Font(Font.SANS_SERIF, Font.BOLD, 8);
        t.setFont(fc);
        int n = lignes.size() - 1;
        double yy = rr - n * interligne(fc) / 2 + fc.getSize2D() * 0.8;
        for (int i = 1; i < lignes.size(); i++) {
            String s = lignes.get(i);
            t.drawString(s, (float) (rr - larg(s, fc) / 2), (float) yy);
            yy += interligne(fc);
        }
    }

    /** Encrage irrégulier : bruit basse fréquence et lacunes, comme un tampon réel. */
    private void texturer(BufferedImage buf) {
        int w = buf.getWidth();
        int h = buf.getHeight();
        int[] px = ((DataBufferInt) buf.getRaster().getDataBuffer()).getData();
        int cell = Math.max(4, (int) (echelle * 3));
        int gw = w / cell + 2;
        int gh = h / cell + 2;
        double[] grille = new double[gw * gh];
        for (int i = 0; i < grille.length; i++) {
            grille[i] = r.nextDouble();
        }
        double charge = 0.55 + r.nextDouble() * 0.45;
        long s = r.nextLong() | 1;
        for (int yy = 0; yy < h; yy++) {
            int gy = yy / cell;
            double fy = (yy % cell) / (double) cell;
            for (int xx = 0; xx < w; xx++) {
                int i = yy * w + xx;
                int a = px[i] >>> 24;
                if (a == 0) {
                    continue;
                }
                int gx = xx / cell;
                double fx = (xx % cell) / (double) cell;
                double n = (grille[gy * gw + gx] * (1 - fx) + grille[gy * gw + gx + 1] * fx) * (1 - fy)
                        + (grille[(gy + 1) * gw + gx] * (1 - fx) + grille[(gy + 1) * gw + gx + 1] * fx) * fy;
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                double fin = ((s >>> 40) & 0xFFFF) / 65535.0;
                double k = charge * (0.35 + 0.9 * n) * (0.75 + 0.35 * fin);
                int na = (int) Math.max(0, Math.min(255, a * k));
                px[i] = (na << 24) | (px[i] & 0xFFFFFF);
            }
        }
    }
}
