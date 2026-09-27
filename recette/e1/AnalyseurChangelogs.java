import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Recette E1 — analyse statique des changelogs Liquibase et revue de configuration.
 *
 * <p>Complète verifier-socle.sh (qui lit la base) par ce qui ne se voit que dans les
 * sources : nom des fichiers de changeset, rollback de chaque changeset, étiquetage
 * data-initial, absence de DDL hors Liquibase (ddl-auto, schema.sql, Flyway), amorçage
 * par code Java. JDK 17 seul, sans dépendance : exécuté en mode fichier source
 * ({@code java AnalyseurChangelogs.java}), conformément à la décision D5 (aucun Python).
 *
 * <p>Usage : java AnalyseurChangelogs.java [--backend CHEMIN] [--maitre FICHIER]
 * Sortie : RESULTAT|id|OK/ECHEC/AVERT/NA|libellé|détail puis BILAN ; code 1 si un ECHEC.
 * Références DAT V3 : §2.2, §4.2.1, §4.2.2, §12.1.
 */
public class AnalyseurChangelogs {

    static final Pattern NOM_FICHIER = Pattern.compile("^(\\d{12})_[a-z0-9]+(?:_[a-z0-9]+)*\\.xml$");
    static final Pattern SNAKE = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");

    /** Changements dont Liquibase génère seul le retour arrière ; tout autre exige un rollback. */
    static final Set<String> AUTO_REVERSIBLES = Set.of("addColumn", "addDefaultValue", "addForeignKeyConstraint",
            "addLookupTable", "addNotNullConstraint", "addPrimaryKey", "addUniqueConstraint", "createIndex",
            "createSequence", "createTable", "createView", "dropNotNullConstraint", "renameColumn",
            "renameSequence", "renameTable", "renameView", "tagDatabase");
    static final Set<String> NON_CHANGEMENTS = Set.of("comment", "preConditions", "rollback", "validCheckSum", "modifySql");
    static final Set<String> CHANGEMENTS_DONNEES = Set.of("insert", "update", "delete", "loadData", "loadUpdateData");
    static final Set<String> DESTRUCTIFS = Set.of("dropTable", "dropColumn", "modifyDataType", "renameColumn",
            "renameTable", "mergeColumns", "dropView");
    /**
     * Amorçage de valeurs fixes (ce que §4.2.1 réserve aux changesets data-initial) :
     * INSERT … VALUES et COPY. Les transformations de données existantes (INSERT … SELECT,
     * UPDATE, DELETE d'une reprise ou d'un changement de structure) sont des migrations, pas
     * un amorçage : elles ne portent pas l'étiquette et ne sont pas signalées.
     */
    static final Pattern SQL_DONNEES = Pattern.compile("\\b(insert\\s+into\\s+[\\w.${}\"]+\\s*(\\([^)]*\\))?\\s*values|copy\\s+\\w)",
            Pattern.CASE_INSENSITIVE);
    /** Corps de fonctions ($$ … $$) : leur SQL s'exécute plus tard, pas pendant la migration. */
    static final Pattern CORPS_FONCTION = Pattern.compile("\\$([a-z_]*)\\$.*?\\$\\1\\$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Référentiels métier : créés depuis l'interface seulement (DAT §4.2.1, principe P1). */
    static final Set<String> REFERENTIELS = Set.of("type_document", "index_def", "plan_indexation", "plan_index", "noeud",
            "regle_workflow", "regle_validateur", "groupe_ged", "groupe_membre", "habilitation", "application", "cle_api",
            "cle_api_portee", "document", "utilisateur");

    static final String[][] CATALOGUE = {
            {"E1-A01", "Changelog maître présent [4.2.2]"},
            {"E1-A02", "Le maître ne fait qu'inclure des fichiers [4.2.2]"},
            {"E1-A03", "Un fichier par évolution nommé AAAAMMJJHHmm_objet.xml [4.2.2]"},
            {"E1-A04", "Ordre d'application déterministe et chronologique [4.2.2]"},
            {"E1-A05", "Rollback explicite sur chaque changeset non auto-réversible [4.2.2]"},
            {"E1-A06", "Rollback vide justifié [4.2.2]"},
            {"E1-A07", "Changesets de données étiquetés data-initial [4.2.1]"},
            {"E1-A08", "Aucun référentiel métier amorcé par changeset [4.2.1]"},
            {"E1-A09", "Identifiants de changeset uniques [4.2.2]"},
            {"E1-A10", "Objets déclarés en snake_case, idx_, uk_, fk_, ck_ [4.2.2]"},
            {"E1-A11", "Colonnes id déclarées en uuid [12.1]"},
            {"E1-A12", "Opérations destructives signalées pour revue expand/contract [4.2.2]"},
            {"E1-A20", "Hibernate ne modifie pas le schéma (ddl-auto validate ou none) [4.2.1]"},
            {"E1-A21", "Liquibase présent, Flyway absent des dépendances [2.2]"},
            {"E1-A22", "Aucun script schema.sql / data.sql hors Liquibase [4.2.1]"},
            {"E1-A23", "Pas d'amorçage de données par code Java au démarrage [4.2.1]"},
            {"E1-A24", "Pilote PostgreSQL présent, MySQL retiré [2.2]"},
            {"E1-A25", "Profil de test sur PostgreSQL (pas H2) [critère de sortie E1]"},
    };

    record Constat(String gravite, String texte) {}

    final Map<String, List<Constat>> constats = new LinkedHashMap<>();
    final Map<String, String> nonApplicables = new LinkedHashMap<>();

    AnalyseurChangelogs() {
        for (String[] c : CATALOGUE) {
            constats.put(c[0], new ArrayList<>());
        }
    }

    void ajouter(String id, String gravite, String texte) {
        constats.get(id).add(new Constat(gravite, texte));
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path depot = Path.of(System.getProperty("user.dir"));
        Path backend = depot.resolve("backend");
        Path maitre = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--backend" -> backend = Path.of(args[++i]);
                case "--maitre" -> maitre = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("option inconnue : " + args[i]);
            }
        }
        System.exit(new AnalyseurChangelogs().executer(backend.toAbsolutePath().normalize(), maitre, out));
    }

    int executer(Path backend, Path maitre, PrintStream out) throws Exception {
        if (maitre == null) {
            maitre = trouverMaitre(backend);
        }
        if (maitre == null || !Files.exists(maitre)) {
            ajouter("E1-A01", "ERREUR", "aucun changelog maître Liquibase sous " + backend.resolve("src/main/resources"));
            for (String id : List.of("E1-A02", "E1-A03", "E1-A04", "E1-A05", "E1-A06", "E1-A07", "E1-A08", "E1-A09",
                    "E1-A10", "E1-A11", "E1-A12")) {
                nonApplicables.put(id, "pas de changelog à analyser");
            }
        } else {
            out.println("# changelog maître : " + maitre);
            Path racineCp = maitre.getParent();
            for (Path p = maitre.getParent(); p != null; p = p.getParent()) {
                if (p.getFileName() != null && p.getFileName().toString().equals("resources")) {
                    racineCp = p;
                    break;
                }
            }
            List<Path> inclus = fichiersInclus(maitre, racineCp);
            List<String[]> horodatages = new ArrayList<>();
            Set<String> vus = new HashSet<>();
            for (Path f : inclus) {
                String nom = f.getFileName().toString();
                if (!Files.exists(f)) {
                    ajouter("E1-A03", "ERREUR", f + " : fichier inclus introuvable");
                    continue;
                }
                Matcher m = NOM_FICHIER.matcher(nom);
                if (!m.matches()) {
                    ajouter("E1-A03", "ERREUR", nom + " : nom hors convention AAAAMMJJHHmm_objet_metier.xml");
                } else {
                    try {
                        LocalDateTime.parse(m.group(1), DateTimeFormatter.ofPattern("uuuuMMddHHmm"));
                        horodatages.add(new String[]{m.group(1), nom});
                    } catch (DateTimeParseException e) {
                        ajouter("E1-A03", "ERREUR", nom + " : horodatage " + m.group(1) + " invalide");
                    }
                }
                if (nom.endsWith(".xml")) {
                    analyserChangelog(f, vus);
                }
            }
            for (int i = 1; i < horodatages.size(); i++) {
                if (horodatages.get(i)[0].compareTo(horodatages.get(i - 1)[0]) < 0) {
                    ajouter("E1-A04", "AVERT", horodatages.get(i)[1] + " inclus après " + horodatages.get(i - 1)[1]
                            + " (ordre non chronologique)");
                }
            }
            out.println("# " + inclus.size() + " fichier(s) de changeset analysé(s)");
        }
        revueConfiguration(backend);
        return rapporter(out);
    }

    // ------------------------------------------------------------------ changelogs

    static Document lireXml(Path f) throws Exception {
        DocumentBuilderFactory fabrique = DocumentBuilderFactory.newInstance();
        fabrique.setNamespaceAware(true);
        // Aucune entité externe : un changelog ne doit rien aller chercher ailleurs.
        fabrique.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return fabrique.newDocumentBuilder().parse(f.toFile());
    }

    static String local(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    static List<Element> enfants(Element e) {
        List<Element> r = new ArrayList<>();
        NodeList l = e.getChildNodes();
        for (int i = 0; i < l.getLength(); i++) {
            if (l.item(i) instanceof Element el) {
                r.add(el);
            }
        }
        return r;
    }

    static List<Element> descendants(Element e) {
        List<Element> r = new ArrayList<>();
        for (Element c : enfants(e)) {
            r.add(c);
            r.addAll(descendants(c));
        }
        return r;
    }

    static Path trouverMaitre(Path backend) throws IOException {
        Path racine = backend.resolve("src/main/resources");
        if (!Files.isDirectory(racine)) {
            return null;
        }
        try (Stream<Path> s = Files.walk(racine)) {
            return s.filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> Pattern.compile("(master|maitre|changelog)", Pattern.CASE_INSENSITIVE)
                            .matcher(p.getFileName().toString()).find())
                    .filter(p -> !NOM_FICHIER.matcher(p.getFileName().toString()).matches())
                    .filter(p -> debut(p).contains("databaseChangeLog"))
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
    }

    static String debut(Path p) {
        try {
            byte[] b = Files.readAllBytes(p);
            return new String(b, 0, Math.min(b.length, 4000), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    List<Path> fichiersInclus(Path maitre, Path racineCp) throws Exception {
        List<Path> inclus = new ArrayList<>();
        for (Element el : enfants(lireXml(maitre).getDocumentElement())) {
            String nom = local(el);
            boolean relatif = "true".equals(el.getAttribute("relativeToChangelogFile"));
            Path base = relatif ? maitre.getParent() : racineCp;
            switch (nom) {
                case "changeSet" -> ajouter("E1-A02", "ERREUR", maitre.getFileName() + " : changeSet "
                        + el.getAttribute("id") + " écrit dans le maître");
                case "include" -> {
                    String f = sansClasspath(el.getAttribute("file"));
                    Path p = base.resolve(f).normalize();
                    if (!Files.exists(p)) {
                        p = maitre.getParent().resolve(f).normalize();
                    }
                    inclus.add(p);
                }
                case "includeAll" -> {
                    Path d = base.resolve(sansClasspath(el.getAttribute("path"))).normalize();
                    try (Stream<Path> s = Files.walk(d)) {
                        inclus.addAll(s.filter(Files::isRegularFile).sorted().collect(Collectors.toList()));
                    }
                }
                default -> { }
            }
        }
        return inclus;
    }

    static String sansClasspath(String s) {
        String r = s.startsWith("classpath:") ? s.substring("classpath:".length()) : s;
        return r.replaceFirst("^/+", "");
    }

    void analyserChangelog(Path fichier, Set<String> vus) {
        Element racine;
        try {
            racine = lireXml(fichier).getDocumentElement();
        } catch (Exception e) {
            ajouter("E1-A03", "ERREUR", fichier.getFileName() + " : XML illisible (" + e.getMessage() + ")");
            return;
        }
        List<Element> changesets = new ArrayList<>();
        if (local(racine).equals("changeSet")) {
            changesets.add(racine);
        }
        descendants(racine).stream().filter(e -> local(e).equals("changeSet")).forEach(changesets::add);
        for (Element cs : changesets) {
            String ident = cs.getAttribute("id") + "|" + cs.getAttribute("author") + "|" + fichier.getFileName();
            String ref = fichier.getFileName() + "::" + cs.getAttribute("id");
            if (!vus.add(ident)) {
                ajouter("E1-A09", "ERREUR", ref + " : identifiant dupliqué");
            }
            List<Element> changements = enfants(cs).stream().filter(e -> !NON_CHANGEMENTS.contains(local(e))).toList();
            List<Element> rollbacks = enfants(cs).stream().filter(e -> local(e).equals("rollback")).toList();
            Set<String> noms = changements.stream().map(AnalyseurChangelogs::local).collect(Collectors.toCollection(TreeSet::new));
            List<String> nonAuto = noms.stream().filter(n -> !AUTO_REVERSIBLES.contains(n)).toList();
            if (!nonAuto.isEmpty() && rollbacks.isEmpty()) {
                ajouter("E1-A05", "ERREUR", ref + " : " + String.join(", ", nonAuto) + " sans <rollback>");
            }
            for (Element rb : rollbacks) {
                if (enfants(rb).isEmpty() && rb.getTextContent().isBlank()) {
                    ajouter("E1-A06", "AVERT", ref + " : <rollback/> vide (retour arrière volontairement nul, à justifier)");
                }
            }
            String contextes = (cs.getAttribute("context") + " " + cs.getAttribute("contextFilter") + " "
                    + cs.getAttribute("labels")).toLowerCase(Locale.ROOT);
            String sql = CORPS_FONCTION.matcher(changements.stream()
                    .filter(e -> local(e).equals("sql") || local(e).equals("createProcedure"))
                    .map(Element::getTextContent).collect(Collectors.joining(" "))).replaceAll(" ");
            boolean donnees = noms.stream().anyMatch(CHANGEMENTS_DONNEES::contains) || SQL_DONNEES.matcher(sql).find();
            if (donnees && !contextes.contains("data-initial")) {
                ajouter("E1-A07", "ERREUR", ref + " : changement de données sans contexte ni label data-initial");
            }
            if (donnees) {
                Set<String> cibles = new TreeSet<>();
                changements.stream().filter(e -> CHANGEMENTS_DONNEES.contains(local(e)))
                        .forEach(e -> cibles.add(e.getAttribute("tableName")));
                Matcher m = Pattern.compile("insert\\s+into\\s+(?:\\w+\\.)?\"?(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
                while (m.find()) {
                    cibles.add(m.group(1).toLowerCase(Locale.ROOT));
                }
                cibles.stream().filter(REFERENTIELS::contains)
                        .forEach(t -> ajouter("E1-A08", "ERREUR", ref + " : alimente le référentiel métier " + t));
            }
            noms.stream().filter(DESTRUCTIFS::contains).forEach(n -> ajouter("E1-A12", "AVERT",
                    ref + " : " + n + " (vérifier expand/contract, sauvegarde ciblée, rollback testé en UAT)"));
            controlerNoms(cs, ref);
        }
    }

    void controlerNoms(Element cs, String ref) {
        for (Element e : descendants(cs)) {
            String n = local(e);
            if (n.equals("createTable") || n.equals("renameTable")) {
                String t = !e.getAttribute("tableName").isEmpty() ? e.getAttribute("tableName") : e.getAttribute("newTableName");
                if (!t.isEmpty() && !SNAKE.matcher(t).matches()) {
                    ajouter("E1-A10", "ERREUR", ref + " : table « " + t + " » non snake_case");
                }
            }
            if (n.equals("column")) {
                String col = e.getAttribute("name");
                if (!col.isEmpty() && !SNAKE.matcher(col).matches()) {
                    ajouter("E1-A10", "ERREUR", ref + " : colonne « " + col + " » non snake_case");
                }
                String type = e.getAttribute("type");
                if (col.equals("id") && !type.isEmpty() && !type.toLowerCase(Locale.ROOT).contains("uuid")) {
                    ajouter("E1-A11", "ERREUR", ref + " : colonne id de type " + type);
                }
                if ("true".equalsIgnoreCase(e.getAttribute("autoIncrement"))) {
                    ajouter("E1-A11", "ERREUR", ref + " : colonne " + col + " autoIncrement");
                }
            }
            if (n.equals("constraints")) {
                prefixe(ref, "foreignKeyName", e.getAttribute("foreignKeyName"), "fk_");
                prefixe(ref, "uniqueConstraintName", e.getAttribute("uniqueConstraintName"), "uk_");
            }
            if (n.equals("createIndex")) {
                String v = e.getAttribute("indexName");
                boolean ok = v.startsWith("idx_") || ("true".equalsIgnoreCase(e.getAttribute("unique")) && v.startsWith("uk_"));
                if (!ok) {
                    ajouter("E1-A10", "ERREUR", ref + " : index « " + v + " » sans préfixe idx_");
                }
            }
            if (n.equals("addForeignKeyConstraint") && !e.getAttribute("constraintName").startsWith("fk_")) {
                ajouter("E1-A10", "ERREUR", ref + " : addForeignKeyConstraint « " + e.getAttribute("constraintName") + " » sans préfixe fk_");
            }
            if (n.equals("addUniqueConstraint") && !e.getAttribute("constraintName").startsWith("uk_")) {
                ajouter("E1-A10", "ERREUR", ref + " : addUniqueConstraint « " + e.getAttribute("constraintName") + " » sans préfixe uk_");
            }
            if (n.equals("sql")) {
                String texte = e.getTextContent();
                Matcher m = Pattern.compile("constraint\\s+\"?(\\w+)\"?\\s+(check|unique|foreign\\s+key)", Pattern.CASE_INSENSITIVE).matcher(texte);
                while (m.find()) {
                    String genre = m.group(2).toLowerCase(Locale.ROOT);
                    String attendu = genre.equals("check") ? "ck_" : genre.equals("unique") ? "uk_" : "fk_";
                    if (!m.group(1).toLowerCase(Locale.ROOT).startsWith(attendu)) {
                        ajouter("E1-A10", "ERREUR", ref + " : contrainte SQL « " + m.group(1) + " » sans préfixe " + attendu);
                    }
                }
                Matcher i = Pattern.compile("create\\s+(unique\\s+)?index\\s+(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?\"?(\\w+)",
                        Pattern.CASE_INSENSITIVE).matcher(texte);
                while (i.find()) {
                    if (!(i.group(2).startsWith("idx_") || (i.group(1) != null && i.group(2).startsWith("uk_")))) {
                        ajouter("E1-A10", "ERREUR", ref + " : index SQL « " + i.group(2) + " » sans préfixe idx_");
                    }
                }
            }
        }
    }

    void prefixe(String ref, String attribut, String valeur, String prefixe) {
        if (!valeur.isEmpty() && !valeur.startsWith(prefixe)) {
            ajouter("E1-A10", "ERREUR", ref + " : " + attribut + "=« " + valeur + " » sans préfixe " + prefixe);
        }
    }

    // ------------------------------------------------------------------ configuration

    static String lire(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static List<Path> fichiers(Path racine, String regexNom) throws IOException {
        if (!Files.isDirectory(racine)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(racine)) {
            return s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches(regexNom)).sorted().toList();
        }
    }

    void revueConfiguration(Path backend) throws IOException {
        Path res = backend.resolve("src/main/resources");
        for (Path f : fichiers(res, "application.*\\.(ya?ml|properties)")) {
            if (f.getParent().equals(res)) {
                String t = lire(f);
                String nom = f.getFileName().toString();
                Matcher m = Pattern.compile("ddl-auto\\s*[:=]\\s*\\$?\\{?([\\w:-]+)").matcher(t);
                while (m.find()) {
                    String[] parts = m.group(1).split(":");
                    String valeur = parts[parts.length - 1];
                    if (!valeur.equals("validate") && !valeur.equals("none")) {
                        ajouter("E1-A20", "ERREUR", nom + " : ddl-auto = " + m.group(1));
                    }
                }
                if (Pattern.compile("^\\s*flyway\\s*:", Pattern.MULTILINE).matcher(t).find() && t.matches("(?s).*enabled\\s*:\\s*true.*")) {
                    ajouter("E1-A21", "ERREUR", nom + " : configuration Flyway activée");
                }
                Matcher init = Pattern.compile("sql\\s*:\\s*\\R\\s*init\\s*:\\s*\\R\\s*mode\\s*:\\s*(\\w+)").matcher(t);
                if (init.find() && !init.group(1).equals("never")) {
                    ajouter("E1-A22", "ERREUR", nom + " : spring.sql.init.mode = " + init.group(1));
                }
                if (t.contains("jdbc:h2")) {
                    ajouter("E1-A25", "AVERT", nom + " : URL H2 encore présente dans un profil applicatif");
                }
                if (t.contains("jdbc:mysql")) {
                    ajouter("E1-A24", "ERREUR", nom + " : URL MySQL encore présente");
                }
            }
        }
        for (Path f : fichiers(res, "(schema|data|import)\\.sql")) {
            ajouter("E1-A22", "ERREUR", backend.relativize(f) + " présent (exécuté hors Liquibase)");
        }
        for (Path f : fichiers(res, "V.*__.*\\.sql")) {
            if (f.toString().replace('\\', '/').contains("db/migration")) {
                ajouter("E1-A22", "ERREUR", backend.relativize(f) + " : migration Flyway résiduelle");
            }
        }
        Path pom = backend.resolve("pom.xml");
        if (Files.exists(pom)) {
            String p = lire(pom);
            if (p.toLowerCase(Locale.ROOT).contains("flyway")) {
                ajouter("E1-A21", "ERREUR", "pom.xml : dépendance ou plugin Flyway présent");
            }
            if (!p.contains("liquibase-core")) {
                ajouter("E1-A21", "ERREUR", "pom.xml : liquibase-core absent");
            }
            if (!p.contains("org.postgresql")) {
                ajouter("E1-A24", "ERREUR", "pom.xml : pilote org.postgresql absent");
            }
            if (p.contains("mysql-connector")) {
                ajouter("E1-A24", "AVERT", "pom.xml : pilote MySQL encore présent (acceptable seulement pour l'outil de reprise)");
            }
        }
        Pattern amorce = Pattern.compile("implements\\s+(CommandLineRunner|ApplicationRunner)|ApplicationReadyEvent");
        Pattern ecriture = Pattern.compile("\\.save(All)?\\(");
        for (Path f : fichiers(backend.resolve("src/main/java"), ".*\\.java")) {
            String t = lire(f);
            if (amorce.matcher(t).find() && ecriture.matcher(t).find()) {
                ajouter("E1-A23", "AVERT", backend.relativize(f) + " : écrit en base au démarrage (revoir : amorçage par changeset data-initial)");
            }
        }
        for (Path f : fichiers(backend.resolve("src/test/resources"), "application.*\\.ya?ml")) {
            if (lire(f).contains("jdbc:h2")) {
                ajouter("E1-A25", "ERREUR", "src/test/resources/" + f.getFileName() + " : les tests tournent encore sur H2");
            }
        }
    }

    // ------------------------------------------------------------------ rapport

    int rapporter(PrintStream out) {
        int ok = 0, echec = 0, avert = 0, na = 0;
        for (String[] c : CATALOGUE) {
            List<Constat> l = constats.get(c[0]);
            List<String> err = l.stream().filter(x -> x.gravite().equals("ERREUR")).map(Constat::texte).toList();
            List<String> av = l.stream().filter(x -> x.gravite().equals("AVERT")).map(Constat::texte).toList();
            List<String> base = err.isEmpty() ? av : err;
            String detail = String.join(" ; ", base.subList(0, Math.min(6, base.size())))
                    + (base.size() > 6 ? " … (" + base.size() + " au total)" : "");
            String statut;
            if (!err.isEmpty()) {
                statut = "ECHEC"; echec++; detail = err.size() + " écart(s) : " + detail;
            } else if (!av.isEmpty()) {
                statut = "AVERT"; avert++; detail = av.size() + " point(s) : " + detail;
            } else if (nonApplicables.containsKey(c[0])) {
                statut = "NA"; na++; detail = nonApplicables.get(c[0]);
            } else {
                statut = "OK"; ok++; detail = "";
            }
            out.println("RESULTAT|" + c[0] + "|" + statut + "|" + c[1] + "|" + detail);
        }
        out.println("BILAN|E1 changelogs et configuration|ok=" + ok + "|echec=" + echec + "|avert=" + avert + "|na=" + na);
        return echec > 0 ? 1 : 0;
    }
}
