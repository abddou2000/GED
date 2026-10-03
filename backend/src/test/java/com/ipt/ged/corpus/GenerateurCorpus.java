package com.ipt.ged.corpus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Générateur des corpus volumiques de T-028 (qualité de l'OCR) et P-14
 * (volumétrie et débit). Java seul, sans Python (revue client D5).
 *
 * <pre>
 *   --jeu t028|p14|tous      jeu à produire (défaut : tous)
 *   --sortie DOSSIER         dossier de sortie (défaut : corpus-sortie)
 *   --graine N               graine du tirage (défaut : 2026) ; même graine, même corpus
 *   --pages N                pages à produire (défaut : 300 pour T-028, 20 000 pour P-14)
 *   --transcriptions N       pages de T-028 avec texte de référence (défaut : 100)
 *   --fils N                 fils de calcul (défaut : nombre de cœurs)
 * </pre>
 *
 * <p>Ce corpus est synthétique : il couvre tampons, manuscrit et tableaux, mais
 * ne remplace pas l'échantillon réel du bureau d'ordre attendu pour T-028.
 */
public final class GenerateurCorpus {

    private final Polices polices = Polices.charger();
    private final long graine;
    private final int fils;

    private GenerateurCorpus(long graine, int fils) {
        this.graine = graine;
        this.fils = fils;
    }

    public static void main(String[] args) throws Exception {
        String jeu = "tous";
        Path sortie = Path.of("corpus-sortie");
        long graine = 2026;
        Integer pages = null;
        int transcriptions = 100;
        int fils = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jeu" -> jeu = args[++i].toLowerCase(Locale.ROOT);
                case "--sortie" -> sortie = Path.of(args[++i]);
                case "--graine" -> graine = Long.parseLong(args[++i]);
                case "--pages" -> pages = Integer.parseInt(args[++i]);
                case "--transcriptions" -> transcriptions = Integer.parseInt(args[++i]);
                case "--fils" -> fils = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Option inconnue : " + args[i]);
            }
        }
        GenerateurCorpus g = new GenerateurCorpus(graine, fils);
        if (jeu.equals("t028") || jeu.equals("tous")) {
            g.t028(sortie.resolve("t028"), pages != null ? pages : 300, transcriptions);
        }
        if (jeu.equals("p14") || jeu.equals("tous")) {
            g.p14(sortie.resolve("p14"), pages != null ? pages : 20_000);
        }
    }

    // ================================================================= T-028

    private record LigneT028(String id, String fichier, TypeDoc type, int page, int pages, Numeriseur.Reglages rg,
                             double inclinaison, boolean tampon, boolean manuscrit, boolean tableau,
                             boolean transcrite, long octets) {
    }

    void t028(Path dir, int total, int nbTranscrites) throws Exception {
        Files.createDirectories(dir.resolve("pages"));
        Files.createDirectories(dir.resolve("transcriptions"));
        Files.createDirectories(dir.resolve("zones"));
        Random plan = new Random(graine);

        List<TypeDoc> types = new ArrayList<>();
        Map<TypeDoc, Integer> parType = repartir(total, t -> t.poidsT028);
        parType.forEach((t, n) -> types.addAll(Collections.nCopies(n, t)));
        Collections.shuffle(types, plan);

        List<Numeriseur.Qualite> qualites = new ArrayList<>();
        int bonnes = (int) Math.round(total * 0.5);
        int moyennes = (int) Math.round(total * 0.35);
        for (int i = 0; i < total; i++) {
            qualites.add(i < bonnes ? Numeriseur.Qualite.BONNE
                    : i < bonnes + moyennes ? Numeriseur.Qualite.MOYENNE : Numeriseur.Qualite.MAUVAISE);
        }
        Collections.shuffle(qualites, plan);

        // Échantillon transcrit : stratifié par type, au prorata.
        Set<Integer> transcrites = new HashSet<>();
        Map<TypeDoc, Integer> quota = repartirSur(nbTranscrites, parType);
        for (TypeDoc t : TypeDoc.values()) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (types.get(i) == t) {
                    idx.add(i);
                }
            }
            Collections.shuffle(idx, plan);
            transcrites.addAll(idx.subList(0, Math.min(quota.getOrDefault(t, 0), idx.size())));
        }

        AtomicInteger fait = new AtomicInteger();
        List<LigneT028> lignes = executer(total, i -> {
            Random r = new Random(graine * 1_000_003L + i);
            TypeDoc type = types.get(i);
            int nb = type.tirerPages(r);
            int num = Donnees.chance(r, 0.5) ? 1 : 1 + r.nextInt(nb);
            Dossier d = new Dossier(type, r);
            d.article = 1 + (num - 1) * 4;
            d.rangee = 1 + (num - 1) * 25;
            Numeriseur.Reglages rg = Numeriseur.tirer(qualites.get(i), r);
            Page p = new Page(rg.dpi(), r, polices);
            Modeles.dessiner(p, d, num, nb);
            Numeriseur.Scan s = Numeriseur.numeriser(p, rg, r);
            String id = String.format("T028-%03d", i + 1);
            Path img = dir.resolve("pages").resolve(id + "." + rg.format());
            Numeriseur.ecrire(s.image(), img, rg.format(), rg.dpi(), rg.jpeg());
            boolean tr = transcrites.contains(i);
            if (tr) {
                List<Page.Zone> zones = ordreDeLecture(p.zones);
                Files.writeString(dir.resolve("transcriptions").resolve(id + ".txt"), texte(zones), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("zones").resolve(id + ".json"), json(id, img.getFileName().toString(),
                        type, rg, s, zones), StandardCharsets.UTF_8);
            }
            int n = fait.incrementAndGet();
            if (n % 25 == 0 || n == total) {
                System.out.printf("T-028 : %d/%d pages%n", n, total);
            }
            return new LigneT028(id, "pages/" + img.getFileName(), type, num, nb, rg, s.inclinaisonDeg(),
                    a(p, "tampon"), a(p, "manuscrit") || a(p, "tableau_manuscrit"),
                    a(p, "tableau") || a(p, "tableau_manuscrit"), tr, Files.size(img));
        });

        StringBuilder csv = new StringBuilder(
                "id;fichier;type_document;page;pages_document;qualite;dpi;mode;format;inclinaison_deg;tampon;manuscrit;tableau;transcription;octets\n");
        for (LigneT028 l : lignes) {
            csv.append(String.join(";", l.id(), l.fichier(), l.type().code, String.valueOf(l.page()),
                    String.valueOf(l.pages()), l.rg().qualite().name().toLowerCase(Locale.ROOT),
                    String.valueOf(l.rg().dpi()), l.rg().mode().name().toLowerCase(Locale.ROOT), l.rg().format(),
                    String.format(Locale.ROOT, "%.2f", l.inclinaison()), on(l.tampon()), on(l.manuscrit()),
                    on(l.tableau()), on(l.transcrite()), String.valueOf(l.octets()))).append('\n');
        }
        Files.writeString(dir.resolve("manifeste.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("LISEZ-MOI.txt"), resumeT028(lignes), StandardCharsets.UTF_8);
        System.out.println("T-028 : terminé dans " + dir.toAbsolutePath());
    }

    private static boolean a(Page p, String type) {
        return p.zones.stream().anyMatch(z -> z.type().equals(type));
    }

    private static String on(boolean b) {
        return b ? "oui" : "non";
    }

    /** Ordre de lecture : de haut en bas, et de gauche à droite pour les zones d'une même ligne. */
    static List<Page.Zone> ordreDeLecture(List<Page.Zone> zones) {
        List<Page.Zone> tri = new ArrayList<>(zones);
        tri.sort(Comparator.comparingDouble(z -> z.y() + Math.min(z.h(), 14) / 2));
        List<Page.Zone> res = new ArrayList<>();
        int i = 0;
        while (i < tri.size()) {
            double ref = tri.get(i).y() + Math.min(tri.get(i).h(), 14) / 2;
            int j = i;
            while (j < tri.size() && tri.get(j).y() + Math.min(tri.get(j).h(), 14) / 2 - ref < 7) {
                j++;
            }
            List<Page.Zone> rang = new ArrayList<>(tri.subList(i, j));
            rang.sort(Comparator.comparingDouble(Page.Zone::x));
            res.addAll(rang);
            i = j;
        }
        return res;
    }

    static String texte(List<Page.Zone> zones) {
        StringBuilder sb = new StringBuilder();
        for (Page.Zone z : zones) {
            for (String l : z.lignes()) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    private static String json(String id, String image, TypeDoc type, Numeriseur.Reglages rg, Numeriseur.Scan s,
                               List<Page.Zone> zones) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"id\": ").append(q(id))
                .append(",\n  \"image\": ").append(q(image))
                .append(",\n  \"type_document\": ").append(q(type.code))
                .append(",\n  \"dpi\": ").append(rg.dpi())
                .append(",\n  \"largeur_px\": ").append(s.image().getWidth())
                .append(",\n  \"hauteur_px\": ").append(s.image().getHeight())
                .append(",\n  \"inclinaison_deg\": ").append(String.format(Locale.ROOT, "%.2f", s.inclinaisonDeg()))
                .append(",\n  \"zones\": [");
        for (int i = 0; i < zones.size(); i++) {
            Page.Zone z = zones.get(i);
            int[] b = boite(z, s);
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"type\": ").append(q(z.type()))
                    .append(", \"boite_px\": [").append(b[0]).append(", ").append(b[1]).append(", ").append(b[2])
                    .append(", ").append(b[3]).append("], \"lignes\": [");
            for (int k = 0; k < z.lignes().size(); k++) {
                sb.append(k == 0 ? "" : ", ").append(q(z.lignes().get(k)));
            }
            sb.append("]}");
        }
        return sb.append("\n  ]\n}\n").toString();
    }

    /** Boîte englobante de la zone dans l'image numérisée (après inclinaison), en pixels : x, y, largeur, hauteur. */
    private static int[] boite(Page.Zone z, Numeriseur.Scan s) {
        double[][] coins = {{z.x(), z.y()}, {z.x() + z.l(), z.y()}, {z.x(), z.y() + z.h()}, {z.x() + z.l(), z.y() + z.h()}};
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (double[] c : coins) {
            Point2D p = s.pointsVersPixels().transform(new Point2D.Double(c[0], c[1]), null);
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
        }
        int w = s.image().getWidth();
        int h = s.image().getHeight();
        int bx = (int) Math.max(0, Math.floor(x0));
        int by = (int) Math.max(0, Math.floor(y0));
        int bx1 = (int) Math.min(w, Math.ceil(x1));
        int by1 = (int) Math.min(h, Math.ceil(y1));
        return new int[]{bx, by, Math.max(0, bx1 - bx), Math.max(0, by1 - by)};
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String resumeT028(List<LigneT028> l) {
        StringBuilder sb = new StringBuilder();
        sb.append("CORPUS T-028 (qualité de l'OCR, §4.3.2) — SYNTHÉTIQUE\n\n");
        sb.append(l.size()).append(" pages numérisées, dont ")
                .append(l.stream().filter(LigneT028::transcrite).count()).append(" avec texte de référence.\n\n");
        sb.append("Ce corpus ne remplace pas l'échantillon réel du bureau d'ordre (Q09) :\n");
        sb.append("il sert à rôder le banc de mesure et à situer Tesseract sur tampons,\n");
        sb.append("manuscrit et tableaux en attendant les 300 pages réelles.\n\n");
        sb.append("Répartition par type (pages / transcrites) :\n");
        for (TypeDoc t : TypeDoc.values()) {
            long n = l.stream().filter(x -> x.type() == t).count();
            long tr = l.stream().filter(x -> x.type() == t && x.transcrite()).count();
            sb.append(String.format("  %-22s %4d / %3d%n", t.libelle, n, tr));
        }
        sb.append("\nCaractéristiques (pages) :\n");
        sb.append(String.format("  avec tampon     %4d%n", l.stream().filter(LigneT028::tampon).count()));
        sb.append(String.format("  avec manuscrit  %4d%n", l.stream().filter(LigneT028::manuscrit).count()));
        sb.append(String.format("  avec tableau    %4d%n", l.stream().filter(LigneT028::tableau).count()));
        for (Numeriseur.Qualite q : Numeriseur.Qualite.values()) {
            sb.append(String.format("  qualité %-8s %4d%n", q.name().toLowerCase(Locale.ROOT),
                    l.stream().filter(x -> x.rg().qualite() == q).count()));
        }
        for (Numeriseur.Mode m : Numeriseur.Mode.values()) {
            sb.append(String.format("  mode %-11s %4d%n", m.name().toLowerCase(Locale.ROOT),
                    l.stream().filter(x -> x.rg().mode() == m).count()));
        }
        sb.append("""

                Conventions du texte de référence (transcriptions/*.txt) :
                  - une ligne de texte par ligne, en UTF-8 ;
                  - zones dans l'ordre de lecture (haut → bas, gauche → droite) ;
                  - tableaux : une rangée par ligne, cellules séparées par une tabulation ;
                  - le texte des tampons et des annotations manuscrites est inclus ;
                  - signatures, logos et transparence du verso n'ont pas de texte.
                zones/*.json donne pour chaque zone son type (imprime, tableau, tableau_manuscrit,
                manuscrit, tampon), sa boîte en pixels dans l'image et ses lignes : de quoi mesurer
                le taux d'erreur par catégorie, indépendamment de l'ordre de lecture.
                """);
        return sb.toString();
    }

    // ================================================================== P-14

    private record DocP14(int index, TypeDoc type, int pages, long graine) {
    }

    private record LigneP14(String fichier, TypeDoc type, int pages, Numeriseur.Reglages rg, long octets) {
    }

    void p14(Path dir, int totalPages) throws Exception {
        Random plan = new Random(graine ^ 0x5DEECE66DL);
        Map<TypeDoc, Integer> poids = new EnumMap<>(TypeDoc.class);
        int somme = 0;
        for (TypeDoc t : TypeDoc.values()) {
            poids.put(t, t.poidsP14);
            somme += t.poidsP14;
        }
        List<DocP14> docs = new ArrayList<>();
        int reste = totalPages;
        while (reste > 0) {
            int u = plan.nextInt(somme);
            TypeDoc type = null;
            for (TypeDoc t : TypeDoc.values()) {
                u -= poids.get(t);
                if (u < 0) {
                    type = t;
                    break;
                }
            }
            int n = Math.min(type.tirerPages(plan), reste);
            docs.add(new DocP14(docs.size(), type, n, plan.nextLong()));
            reste -= n;
        }
        for (TypeDoc t : TypeDoc.values()) {
            Files.createDirectories(dir.resolve("documents").resolve(t.code));
        }
        AtomicInteger pagesFaites = new AtomicInteger();
        long debut = System.nanoTime();
        List<LigneP14> lignes = executer(docs.size(), i -> {
            DocP14 doc = docs.get(i);
            Random r = new Random(doc.graine());
            Dossier d = new Dossier(doc.type(), r);
            Numeriseur.Reglages rg = Numeriseur.tirer(qualiteP14(r), r);
            String nom = String.format("P14-%05d.pdf", doc.index() + 1);
            Path f = dir.resolve("documents").resolve(doc.type().code).resolve(nom);
            try (PDDocument pdf = new PDDocument()) {
                PDDocumentInformation info = pdf.getDocumentInformation();
                info.setTitle(doc.type().libelle + " " + d.reference);
                info.setProducer("GED – générateur de corpus P-14 (synthétique)");
                for (int num = 1; num <= doc.pages(); num++) {
                    Page p = new Page(rg.dpi(), r, polices);
                    Modeles.dessiner(p, d, num, doc.pages());
                    BufferedImage img = Numeriseur.numeriser(p, rg, r).image();
                    PDImageXObject x = img.getType() == BufferedImage.TYPE_BYTE_BINARY
                            ? CCITTFactory.createFromImage(pdf, img)
                            : JPEGFactory.createFromImage(pdf, img, rg.jpeg(), rg.dpi());
                    PDPage page = new PDPage(PDRectangle.A4);
                    pdf.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                        cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                    }
                    int n = pagesFaites.incrementAndGet();
                    if (n % 500 == 0) {
                        double s = (System.nanoTime() - debut) / 1e9;
                        System.out.printf("P-14 : %d/%d pages (%.1f pages/s)%n", n, totalPages, n / s);
                    }
                }
                pdf.save(f.toFile());
            }
            return new LigneP14("documents/" + doc.type().code + "/" + nom, doc.type(), doc.pages(), rg, Files.size(f));
        });

        StringBuilder csv = new StringBuilder("fichier;type_document;pages;qualite;dpi;mode;octets\n");
        for (LigneP14 l : lignes) {
            csv.append(String.join(";", l.fichier(), l.type().code, String.valueOf(l.pages()),
                    l.rg().qualite().name().toLowerCase(Locale.ROOT), String.valueOf(l.rg().dpi()),
                    l.rg().mode().name().toLowerCase(Locale.ROOT), String.valueOf(l.octets()))).append('\n');
        }
        Files.writeString(dir.resolve("manifeste.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("LISEZ-MOI.txt"), resumeP14(lignes), StandardCharsets.UTF_8);
        System.out.println("P-14 : terminé dans " + dir.toAbsolutePath());
    }

    private static Numeriseur.Qualite qualiteP14(Random r) {
        double u = r.nextDouble();
        return u < 0.6 ? Numeriseur.Qualite.BONNE : u < 0.9 ? Numeriseur.Qualite.MOYENNE : Numeriseur.Qualite.MAUVAISE;
    }

    private static String resumeP14(List<LigneP14> l) {
        long pages = l.stream().mapToLong(LigneP14::pages).sum();
        long octets = l.stream().mapToLong(LigneP14::octets).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("CORPUS P-14 (volumétrie et débit) — SYNTHÉTIQUE\n\n");
        sb.append(String.format("%d documents PDF image (sans couche texte), %d pages, %.2f Go (%.0f Ko/page en moyenne).%n%n",
                l.size(), pages, octets / 1e9, octets / 1024.0 / pages));
        sb.append("Répartition par type (documents / pages) :\n");
        for (TypeDoc t : TypeDoc.values()) {
            sb.append(String.format("  %-22s %6d / %6d%n", t.libelle, l.stream().filter(x -> x.type() == t).count(),
                    l.stream().filter(x -> x.type() == t).mapToLong(LigneP14::pages).sum()));
        }
        sb.append("\nPages par document :\n");
        int[][] tranches = {{1, 1}, {2, 2}, {3, 5}, {6, 10}, {11, 99}};
        for (int[] t : tranches) {
            sb.append(String.format("  %2d–%-2d : %6d documents%n", t[0], t[1],
                    l.stream().filter(x -> x.pages() >= t[0] && x.pages() <= t[1]).count()));
        }
        sb.append("\nModes de numérisation (documents) :\n");
        for (Numeriseur.Mode m : Numeriseur.Mode.values()) {
            sb.append(String.format("  %-11s %6d%n", m.name().toLowerCase(Locale.ROOT),
                    l.stream().filter(x -> x.rg().mode() == m).count()));
        }
        sb.append("""

                Attention : ged.ocr.pages-max vaut 5 par défaut. Pour mesurer le débit en pages
                réellement OCRisées, relever ce plafond (ou compter les pages traitées) pendant P-14.
                """);
        return sb.toString();
    }

    // ============================================================== utilitaires

    private interface Tache<T> {
        T executer(int i) throws Exception;
    }

    private <T> List<T> executer(int n, Tache<T> tache) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(fils);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(ex.submit(() -> tache.executer(k)));
            }
            List<T> res = new ArrayList<>();
            for (Future<T> f : fs) {
                res.add(f.get());
            }
            return res;
        } finally {
            ex.shutdownNow();
        }
    }

    /** Répartit {@code total} selon les poids, au plus fort reste. */
    static Map<TypeDoc, Integer> repartir(int total, java.util.function.ToIntFunction<TypeDoc> poids) {
        Map<TypeDoc, Integer> p = new EnumMap<>(TypeDoc.class);
        for (TypeDoc t : TypeDoc.values()) {
            p.put(t, poids.applyAsInt(t));
        }
        return repartirSur(total, p);
    }

    static Map<TypeDoc, Integer> repartirSur(int total, Map<TypeDoc, Integer> poids) {
        int somme = poids.values().stream().mapToInt(Integer::intValue).sum();
        Map<TypeDoc, Integer> res = new EnumMap<>(TypeDoc.class);
        List<TypeDoc> ordre = new ArrayList<>(poids.keySet());
        int alloue = 0;
        for (TypeDoc t : ordre) {
            int n = (int) ((long) total * poids.get(t) / somme);
            res.put(t, n);
            alloue += n;
        }
        ordre.sort(Comparator.comparingDouble((TypeDoc t) ->
                -(((double) total * poids.get(t) / somme) - res.get(t))));
        for (int i = 0; alloue < total; i++, alloue++) {
            TypeDoc t = ordre.get(i % ordre.size());
            res.put(t, res.get(t) + 1);
        }
        return res;
    }
}
