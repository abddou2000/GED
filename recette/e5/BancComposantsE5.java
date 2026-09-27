import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.DepotClesFichier;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.cles.RotationKek;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.ClientClamd;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.controle.ReglesDepot;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.fichier.stockage.FileStoreDisque;

/**
 * Banc de recette E5 sur les COMPOSANTS livrés par dev3 (vague 1 : le stockage chiffré
 * n'est pas encore branché sur le dépôt HTTP ; la recette de bout en bout par l'API se
 * fera avec verifier-alteration.sh, verifier-antivirus.sh, etc. en vague 2).
 *
 * <p>Exerce les classes de production telles quelles (FileStoreDisque, StockageChiffre,
 * KeystoreKeyProvider, RotationKek, DetecteurTypeReel, ClientClamd, ControleFichiers) sur
 * les jeux de recette, puis fait contrôler le stockage produit par le script bash
 * verifier-aucun-clair.sh, indépendant du code applicatif.
 *
 * <p>Simulateurs, signalés comme tels dans la sortie : dépôt des clés en mémoire (table
 * cle_fichier non encore migrée), clamd factice des tests de dev3 (reconnaît EICAR
 * seulement) sauf si --clamd hote:port désigne un vrai ClamAV.
 *
 * <p>Usage : java -Dfile.encoding=UTF-8 -cp CP BancComposantsE5.java --donnees DIR --travail DIR
 *            [--scanner verifier-aucun-clair.sh] [--clamd hote:port]
 * CP = classpath d'exécution du backend + target/classes + target/test-classes.
 */
public class BancComposantsE5 {

    static PrintStream out;
    static int ok, echec, na;
    static final long MIO = 1024L * 1024L;

    static void resultat(String id, boolean reussi, String libelle, String detail) {
        if (reussi) {
            ok++;
        } else {
            echec++;
        }
        out.println("RESULTAT|" + id + "|" + (reussi ? "OK" : "ECHEC") + "|" + libelle + "|" + detail);
    }

    static void nonApplicable(String id, String libelle, String detail) {
        na++;
        out.println("RESULTAT|" + id + "|NA|" + libelle + "|" + detail);
    }

    public static void main(String[] args) throws Exception {
        out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path donnees = null, travail = null, scanner = null;
        String clamd = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--donnees" -> donnees = Path.of(args[++i]);
                case "--travail" -> travail = Path.of(args[++i]);
                case "--scanner" -> scanner = Path.of(args[++i]);
                case "--clamd" -> clamd = args[++i];
                default -> throw new IllegalArgumentException("option inconnue : " + args[i]);
            }
        }
        final Path donneesF = donnees; // référencé par les lambdas d'essai
        Path racine = travail.resolve("stockage");
        Path coffre = travail.resolve("coffre"); // hors de la racine : la clé n'est jamais à côté des fichiers (§6.1.2)
        Files.createDirectories(racine);
        Files.createDirectories(coffre);

        KeystoreKeyProvider kp = new KeystoreKeyProvider(coffre.resolve("kek.p12"),
                "phrase-de-test-recette-qa".toCharArray(), true);
        DepotClesFichier cles = new DepotClesMemoire();
        StockageChiffre stockage = new StockageChiffre(new FileStoreDisque(racine), kp, cles);
        List<Object> evenements = new CopyOnWriteArrayList<>();

        Object fauxClamd = null;
        String hote;
        int port;
        if (clamd != null) {
            hote = clamd.split(":")[0];
            port = Integer.parseInt(clamd.split(":")[1]);
            out.println("# antivirus : ClamAV réel " + clamd);
        } else {
            fauxClamd = Class.forName("com.ipt.ged.fichier.controle.FauxClamd").getConstructor().newInstance();
            hote = "127.0.0.1";
            port = (int) fauxClamd.getClass().getMethod("port").invoke(fauxClamd);
            out.println("# antivirus : SIMULATEUR (FauxClamd des tests de dev3, port " + port + ")");
        }
        AnalyseurAntivirus antivirus = new ClientClamd(hote, port, 3000, 5000, 64 * 1024);
        List<String> formatsDefaut = List.of("pdf", "tiff", "jpeg", "png", "txt", "csv", "docx", "xlsx", "pptx", "odt", "ods", "odp");
        ControleFichiers controle = new ControleFichiers(new DetecteurTypeReel(), antivirus, stockage,
                evenements::add, 100, 200, formatsDefaut);

        Map<String, String> manifeste = lireManifeste(donnees.resolve("MANIFESTE.csv"));
        ReglesDepot defaut = controle.regles(null, null);

        // K01, K02 — dépôt du jeu autorisé, empreinte du clair (§6.1.4)
        Map<String, UUID> ids = new LinkedHashMap<>();
        List<String> ecarts = new ArrayList<>();
        for (String nom : List.of("pdf_texte_fr_convention.pdf", "pdf_texte_fr_facture.pdf", "pdf_texte_ar_courrier.pdf",
                "scan_fr_courrier.pdf", "scan_ar_courrier.pdf", "image_scan_fr.png", "document_fr.docx",
                "note_texte_brut.txt", "tableau_decomptes.csv")) {
            try {
                ControleFichiers.Depot d = controle.deposer(SourceFichier.de(donnees.resolve(nom)), defaut);
                ids.put(nom, d.stockage().id());
                if (!d.stockage().empreinte().equalsIgnoreCase(manifeste.get(nom))) {
                    ecarts.add(nom + " : empreinte " + d.stockage().empreinte() + " ≠ manifeste");
                }
            } catch (RuntimeException e) {
                ecarts.add(nom + " refusé : " + decrire(e));
            }
        }
        resultat("E5-K01", ids.size() == 9, "Dépôt chiffré des 9 fichiers autorisés du jeu de recette [6.1.1, 6.1.2]",
                ids.size() + "/9 déposés" + (ecarts.isEmpty() ? "" : " ; " + String.join(" ; ", ecarts)));
        resultat("E5-K02", ecarts.isEmpty() && ids.size() == 9, "Empreinte SHA-256 du clair = MANIFESTE.csv [6.1.4]",
                ecarts.isEmpty() ? "9 empreintes identiques" : String.join(" ; ", ecarts));

        // Grand fichier : 3 segments et plus (PDF valide suivi d'octets aléatoires).
        byte[] grand = grandFichier(donnees.resolve("pdf_texte_fr_convention.pdf"));
        UUID idGrand = controle.deposer(SourceFichier.de(grand, "grand.pdf"), defaut).stockage().id();

        // K03 — relecture : déchiffrement transparent
        List<String> ko = new ArrayList<>();
        for (Map.Entry<String, UUID> e : ids.entrySet()) {
            byte[] lu = lire(stockage, e.getValue());
            if (lu == null || !sha256(lu).equalsIgnoreCase(manifeste.get(e.getKey()))) {
                ko.add(e.getKey());
            }
        }
        byte[] luGrand = lire(stockage, idGrand);
        if (luGrand == null || !sha256(luGrand).equals(sha256(grand))) {
            ko.add("grand.pdf");
        }
        resultat("E5-K03", ko.isEmpty(), "Relecture déchiffrée identique à l'original [6.1.2]", ko.isEmpty() ? "10 fichiers" : "écarts : " + ko);

        // K04 — le stockage produit par les composants, contrôlé par le script indépendant
        if (scanner != null) {
            Process p = new ProcessBuilder("bash", scanner.toString(), "--racine", racine.toString())
                    .redirectErrorStream(true).start();
            String sortie = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            sortie.lines().filter(l -> l.startsWith("RESULTAT|") || l.startsWith("BILAN|")).forEach(l -> out.println("#   " + l));
            resultat("E5-K04", code == 0, "Aucun fichier en clair : verifier-aucun-clair.sh sur le stockage des composants [6.1.1, 6.1.2]",
                    "code " + code);
        } else {
            nonApplicable("E5-K04", "Aucun fichier en clair (script indépendant)", "--scanner non fourni");
        }
        long nbCles;
        try (Stream<Path> s = Files.walk(racine)) {
            nbCles = s.filter(f -> f.toString().endsWith(".p12") || f.toString().endsWith(".jks")).count();
        }
        resultat("E5-K05", nbCles == 0, "Aucun keystore sous la racine du stockage [6.1.2]", nbCles + " fichier(s) de clés trouvés");

        // K06 à K09 — altérations détectées à la lecture (GCM), chacune défaite ensuite
        Path fA = new FileStoreDisque(racine).chemin(ids.get("pdf_texte_fr_convention.pdf"));
        Path fB = new FileStoreDisque(racine).chemin(ids.get("pdf_texte_fr_facture.pdf"));
        Path fG = new FileStoreDisque(racine).chemin(idGrand);
        alteration("E5-K06", "Octet inversé au milieu du chiffré : lecture refusée [6.1.2]", stockage, ids.get("pdf_texte_fr_convention.pdf"),
                fA, f -> inverserOctet(f, Files.size(f) / 2));
        alteration("E5-K07", "Fichier chiffré tronqué de 10 octets : lecture refusée [6.1.2]", stockage, ids.get("pdf_texte_fr_convention.pdf"),
                fA, f -> tronquer(f, Files.size(f) - 10));
        alteration("E5-K08", "Chiffré d'un autre fichier substitué : lecture refusée [6.1.2]", stockage, ids.get("pdf_texte_fr_convention.pdf"),
                fA, f -> Files.copy(fB, f, StandardCopyOption.REPLACE_EXISTING));
        alteration("E5-K09", "Grand fichier altéré dans son dernier segment : aucun octet non vérifié livré en entier [6.1.2]", stockage, idGrand,
                fG, f -> inverserOctet(f, Files.size(f) - 100));
        byte[] apres = lire(stockage, ids.get("pdf_texte_fr_convention.pdf"));
        resultat("E5-K10", apres != null && sha256(apres).equalsIgnoreCase(manifeste.get("pdf_texte_fr_convention.pdf")),
                "Après restauration : relecture de nouveau identique", "");

        // K11 — type réel (Tika) sur un type « PDF seul » : 415 et rien d'écrit
        ReglesDepot pdfSeul = controle.regles(null, List.of("pdf"));
        Map<String, String> faux = new LinkedHashMap<>();
        faux.put("faux_pdf_texte.pdf", "faux-texte.pdf");
        faux.put("faux_pdf_executable.pdf", "faux-executable.pdf");
        faux.put("document_fr.docx", "faux-docx.pdf");
        faux.put("image_scan_fr.png", "faux-png.pdf");
        List<String> mal = new ArrayList<>();
        long avant = compter(racine);
        for (Map.Entry<String, String> e : faux.entrySet()) {
            String r = tenter(() -> controle.deposer(SourceFichier.de(Files.readAllBytes(donneesF.resolve(e.getKey())), e.getValue()), pdfSeul));
            if (!r.startsWith("415 FORMAT_NON_AUTORISE")) {
                mal.add(e.getValue() + " → " + r);
            }
        }
        resultat("E5-K11", mal.isEmpty() && compter(racine) == avant, "Texte, exécutable, DOCX, PNG sous extension .pdf : 415 FORMAT_NON_AUTORISE, rien d'écrit [6.1.5]",
                mal.isEmpty() ? "4 refus, stockage inchangé" : String.join(" ; ", mal));
        String vrai = tenter(() -> controle.controler(SourceFichier.de(Files.readAllBytes(donneesF.resolve("pdf_texte_fr_facture.pdf")), "vrai-pdf.txt"), pdfSeul));
        resultat("E5-K12", vrai.startsWith("OK"), "Vrai PDF sous extension .txt accepté : le contenu fait foi [6.1.5]", vrai);

        // K13, K14 — tailles : limite du type (1 Mo) et plafond de plateforme (200 Mo)
        ReglesDepot unMo = controle.regles(1, List.of("pdf"));
        byte[] base = Files.readAllBytes(donnees.resolve("pdf_texte_fr_convention.pdf"));
        String auDela = tenter(() -> controle.deposer(SourceFichier.de(gonfler(base, MIO + 1), "un-mo-plus-un.pdf"), unMo));
        String pile = tenter(() -> controle.deposer(SourceFichier.de(gonfler(base, MIO), "un-mo.pdf"), unMo));
        resultat("E5-K13", auDela.startsWith("413 FICHIER_TROP_VOLUMINEUX") && pile.startsWith("OK"),
                "Limite du type : L + 1 octet → 413, exactement L → accepté [6.1.5]", "L+1 : " + auDela + " ; L : " + pile);
        Path creux = travail.resolve("plafond.pdf");
        try (RandomAccessFile raf = new RandomAccessFile(creux.toFile(), "rw")) {
            raf.write(base);
            raf.setLength(200 * MIO + 1);
        }
        ReglesDepot enorme = controle.regles(500, List.of("pdf"));
        String plafond = tenter(() -> controle.deposer(SourceFichier.de(creux), enorme));
        resultat("E5-K14", enorme.tailleMaxOctets() == 200 * MIO && plafond.startsWith("413"),
                "Type paramétré à 500 Mo ramené au plafond de 200 Mo ; 200 Mio + 1 → 413 [6.1.5]",
                "limite effective " + enorme.tailleMaxOctets() / MIO + " Mo ; " + plafond);
        Files.deleteIfExists(creux);

        // K15 à K17 — antivirus
        byte[] eicar = eicar();
        avant = compter(racine);
        int evenementsAvant = evenements.size();
        String infecte = tenter(() -> controle.deposer(SourceFichier.de(eicar, "facture.txt"), defaut));
        resultat("E5-K15", infecte.startsWith("422 FICHIER_INFECTE") && compter(racine) == avant,
                "EICAR : 422 FICHIER_INFECTE, rien d'écrit [6.1.5]" + (fauxClamd != null ? " (simulateur)" : ""), infecte);
        resultat("E5-K16", evenements.size() == evenementsAvant + 1
                        && evenements.get(evenements.size() - 1) instanceof ControleFichiers.FichierInfecte,
                "Refus antivirus publié comme événement d'audit [6.1.5, 7.4.1]", (evenements.size() - evenementsAvant) + " événement(s)");
        if (fauxClamd != null) {
            fauxClamd.getClass().getMethod("close").invoke(fauxClamd); // clamd arrêté
            avant = compter(racine);
            String ferme = tenter(() -> controle.deposer(SourceFichier.de(donneesF.resolve("note_texte_brut.txt")), defaut));
            resultat("E5-K17", ferme.startsWith("503 ANTIVIRUS_INDISPONIBLE") && compter(racine) == avant,
                    "ClamAV indisponible : fichier SAIN refusé, 503, rien d'écrit (échec fermé) [6.1.5] (simulateur)", ferme);
        } else {
            nonApplicable("E5-K17", "Échec fermé si ClamAV est indisponible", "ClamAV réel : arrêter clamd puis verifier-antivirus.sh --antivirus-arrete");
        }

        // K18 — rotation de la KEK : réenveloppement sans rechiffrer les fichiers
        Map<Path, String> empreintesEnc = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(racine)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                empreintesEnc.put(f, sha256(Files.readAllBytes(f)));
            }
        }
        String ancienne = kp.kekActive();
        RotationKek.Rapport rapport = new RotationKek(kp, cles).executer();
        boolean inchanges = true;
        for (Map.Entry<Path, String> e : empreintesEnc.entrySet()) {
            inchanges &= sha256(Files.readAllBytes(e.getKey())).equals(e.getValue());
        }
        byte[] relu = lire(stockage, ids.get("scan_ar_courrier.pdf"));
        boolean lisible = relu != null && sha256(relu).equalsIgnoreCase(manifeste.get("scan_ar_courrier.pdf"));
        resultat("E5-K18", !rapport.kekActive().equals(ancienne) && cles.compterParKek(ancienne) == 0 && rapport.echecs().isEmpty()
                        && inchanges && lisible,
                "Rotation de la KEK : DEK réenveloppées, fichiers .enc inchangés, documents lisibles [6.1.2]",
                rapport.traitees() + " clé(s) réenveloppée(s), restant sous l'ancienne KEK : " + cles.compterParKek(ancienne)
                        + ", fichiers inchangés : " + inchanges);

        // K19 — destruction cryptographique
        UUID cible = ids.get("tableau_decomptes.csv");
        boolean detruit = stockage.detruire(cible);
        // Lecture directe (pas l'aide lire(), qui absorbe les exceptions) : l'échec doit être visible.
        String apresDestruction = tenter(() -> {
            try (InputStream in = stockage.lire(cible)) {
                return "lu " + in.readAllBytes().length + " octets";
            }
        });
        resultat("E5-K19", detruit && !stockage.existe(cible) && cles.trouver(cible).isEmpty() && !apresDestruction.startsWith("OK"),
                "Destruction : fichier et DEK supprimés, plus aucune lecture possible [6.1.2, 12.5]", apresDestruction);

        out.println("BILAN|E5 composants|ok=" + ok + "|echec=" + echec + "|avert=0|na=" + na);
        System.exit(echec == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ outils

    interface Action {
        void appliquer(Path f) throws IOException;
    }

    interface Essai {
        Object executer() throws Exception;
    }

    static void alteration(String id, String libelle, StockageChiffre stockage, UUID doc, Path fichier, Action action) throws IOException {
        Path sauvegarde = Files.createTempFile("qa-sauvegarde", ".enc");
        Files.copy(fichier, sauvegarde, StandardCopyOption.REPLACE_EXISTING);
        fichier.toFile().setWritable(true);
        action.appliquer(fichier);
        ByteArrayOutputStream recu = new ByteArrayOutputStream();
        String issue;
        try (InputStream in = stockage.lire(doc)) {
            in.transferTo(recu);
            issue = "LECTURE COMPLÈTE (" + recu.size() + " octets livrés)";
        } catch (ErreurFichierException e) {
            issue = "refusée : " + e.statut().value() + " " + e.code() + " après " + recu.size() + " octets";
        } catch (IOException | RuntimeException e) {
            issue = "refusée : " + e.getClass().getSimpleName() + " après " + recu.size() + " octets";
        }
        Files.copy(sauvegarde, fichier, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(sauvegarde);
        resultat(id, issue.startsWith("refusée"), libelle, issue);
    }

    static String tenter(Essai essai) {
        try {
            Object r = essai.executer();
            return "OK" + (r instanceof ControleFichiers.Depot d ? " " + d.typeMime() : r instanceof String s ? " " + s : "");
        } catch (ErreurFichierException e) {
            return e.statut().value() + " " + e.code();
        } catch (Exception e) {
            return "exception " + e.getClass().getSimpleName() + " : " + e.getMessage();
        }
    }

    static String decrire(RuntimeException e) {
        return e instanceof ErreurFichierException f ? f.statut().value() + " " + f.code() : e.getClass().getSimpleName() + " " + e.getMessage();
    }

    static byte[] lire(StockageChiffre stockage, UUID id) {
        try (InputStream in = stockage.lire(id)) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    static long compter(Path racine) throws IOException {
        try (Stream<Path> s = Files.walk(racine)) {
            return s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".enc")).count();
        }
    }

    static void inverserOctet(Path f, long position) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(position);
            int v = raf.read();
            raf.seek(position);
            raf.write(v ^ 1);
        }
    }

    static void tronquer(Path f, long taille) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(taille);
        }
    }

    static byte[] gonfler(byte[] pdf, long taille) {
        byte[] r = new byte[(int) taille];
        System.arraycopy(pdf, 0, r, 0, Math.min(pdf.length, r.length));
        return r;
    }

    static byte[] grandFichier(Path pdf) throws IOException {
        byte[] debut = Files.readAllBytes(pdf);
        byte[] r = new byte[(int) (3 * MIO + 12345)];
        new java.util.Random(26).nextBytes(r);
        System.arraycopy(debut, 0, r, 0, debut.length);
        return r;
    }

    /** Chaîne EICAR reconstituée à l'exécution (jamais d'un seul tenant dans un fichier). */
    static byte[] eicar() {
        return (new StringBuilder("-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X").reverse()
                + new StringBuilder("*H+H$!ELIF-TSET-SURIVITNA").reverse().toString()).getBytes(StandardCharsets.US_ASCII);
    }

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static Map<String, String> lireManifeste(Path f) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8).subList(1, Files.readAllLines(f).size())) {
            String[] c = l.split(";");
            m.put(c[0], c[2]);
        }
        return m;
    }

    /** Table cle_fichier simulée en mémoire : la table n'est pas encore migrée (vague 1). */
    static final class DepotClesMemoire implements DepotClesFichier {
        private final Map<UUID, com.ipt.ged.fichier.cles.CleFichier> lignes = new java.util.TreeMap<>();

        @Override public synchronized void enregistrer(com.ipt.ged.fichier.cles.CleFichier cle) { lignes.put(cle.id(), cle); }
        @Override public synchronized java.util.Optional<com.ipt.ged.fichier.cles.CleFichier> trouver(UUID id) { return java.util.Optional.ofNullable(lignes.get(id)); }
        @Override public synchronized boolean supprimer(UUID id) { return lignes.remove(id) != null; }
        @Override public synchronized List<com.ipt.ged.fichier.cles.CleFichier> lotHorsKek(String kek, UUID apres, int taille) {
            return lignes.values().stream().filter(c -> !c.kekId().equals(kek))
                    .filter(c -> apres == null || c.id().compareTo(apres) > 0).limit(taille).toList();
        }
        @Override public synchronized boolean remplacerEnveloppe(UUID id, String ancienne, com.ipt.ged.fichier.cles.CleEnveloppee nouvelle) {
            var c = lignes.get(id);
            if (c == null || !c.kekId().equals(ancienne)) {
                return false;
            }
            lignes.put(id, new com.ipt.ged.fichier.cles.CleFichier(id, nouvelle.octets(), nouvelle.kekId(), c.algorithme()));
            return true;
        }
        @Override public synchronized long compterParKek(String kek) { return lignes.values().stream().filter(c -> c.kekId().equals(kek)).count(); }
    }
}
