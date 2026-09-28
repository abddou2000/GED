import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Recette E3 — autorisation et confidentialité, de bout en bout par l'API (DAT V3 §6.2.3 A01,
 * §6.4, §12.2, §12.3, §12.4 ; décision D14).
 *
 * <p>Critère de sortie E3 : un utilisateur sans droit ne voit ni ne compte aucun document hors
 * de son périmètre, sur tous les chemins testés.
 *
 * <p>Jeu construit par le script lui-même, par l'API d'administration (aucun accès direct à la
 * base) :
 * <ul>
 *   <li>DÉPOSANT : rôle Direction Générale en portée globale (accès global, VOIR_PRIVE,
 *       VOIR_CONFIDENTIEL) — dépose les documents témoins partout ;</li>
 *   <li>TIERS : Utilisateur standard sur le nœud A seulement (héritage vers A/enfant) ;</li>
 *   <li>SANS_DROIT : Utilisateur standard sur le nœud C (sans document) et sur B/enfant — donc
 *       B lui est visible en simple « passage », sans aucun document ;</li>
 *   <li>témoins (marqueur unique par exécution) : D1 PUBLIC en A, D2 PRIVE en A, D3 CONFIDENTIEL
 *       en A, D4 PUBLIC en A/enfant, D5 PUBLIC en B ; D6 PRIVE en A déposé par TIERS.</li>
 * </ul>
 *
 * <p>Java 17 seul + Jackson du classpath du backend (décision D5 : aucun Python). Lancé par
 * verifier-autorisation.sh. Variables : GED_URL, GED_RECETTE_MOT_DE_PASSE, GED_E3_ADMIN,
 * GED_E3_DEPOSANT, GED_E3_TIERS, GED_E3_SANS_DROIT, GED_E3_FICHIER, et les noms des nœuds
 * GED_E3_NOEUD_A, _A_ENFANT, _B, _B_ENFANT, _C (défauts : jeu de démonstration du profil dev).
 * Option --reinitialiser : retire les habilitations des comptes de test qui ne font pas partie
 * du jeu (sinon le script refuse de conclure sur un jeu qu'il ne maîtrise pas).
 */
public class RecetteAutorisation {

    static final ObjectMapper JSON = new ObjectMapper();
    static PrintStream out;
    static int ok, echec, avert, na;
    static String url;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    record Rep(int code, String corps, java.net.http.HttpHeaders entetes, long nanos) {
        JsonNode json() {
            try {
                return corps == null || corps.isBlank() ? JSON.nullNode() : JSON.readTree(corps);
            } catch (Exception e) {
                return JSON.nullNode();
            }
        }
    }

    static void res(String id, String statut, String lib, String detail) {
        switch (statut) {
            case "OK" -> ok++;
            case "ECHEC" -> echec++;
            case "AVERT" -> avert++;
            default -> na++;
        }
        out.println("RESULTAT|" + id + "|" + statut + "|" + lib + "|" + (detail == null ? "" : detail.replace('|', '/').replace('\n', ' ')));
    }

    static void verif(String id, boolean cond, String lib, String detail) {
        res(id, cond ? "OK" : "ECHEC", lib, detail);
    }

    static String env(String nom, String defaut) {
        String v = System.getenv(nom);
        return v == null || v.isBlank() ? defaut : v;
    }

    // ------------------------------------------------------------------ HTTP

    static Rep appel(String methode, String chemin, String jeton, String typeCorps, byte[] corps) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + chemin)).timeout(Duration.ofSeconds(60));
        if (jeton != null) {
            b.header("Authorization", "Bearer " + jeton);
        }
        if (methode.equals("POST")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString()); // obligatoire sur les créations (§5.3.2)
        }
        if (typeCorps != null) {
            b.header("Content-Type", typeCorps);
        }
        b.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corps));
        long t0 = System.nanoTime();
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Rep(r.statusCode(), r.body(), r.headers(), System.nanoTime() - t0);
    }

    static Rep get(String chemin, String jeton) throws Exception {
        return appel("GET", chemin, jeton, null, null);
    }

    static Rep json(String methode, String chemin, String jeton, Object corps) throws Exception {
        return appel(methode, chemin, jeton, "application/json", JSON.writeValueAsBytes(corps));
    }

    /** Connexion ; attend et réessaie si la limitation de débit (5/min/IP) répond 429. */
    static String connecter(String compte, String mdp) throws Exception {
        for (int essai = 0; essai < 4; essai++) {
            Rep r = json("POST", "/api/v1/auth/login", null, Map.of("identifiant", compte, "motDePasse", mdp));
            if (r.code() == 200) {
                return r.json().path("token").asText();
            }
            if (r.code() != 429) {
                throw new IllegalStateException("connexion de " + compte + " refusée : HTTP " + r.code());
            }
            long attente = r.entetes().firstValueAsLong("Retry-After").orElse(60);
            out.println("# limitation de débit : attente de " + (attente + 1) + " s avant de connecter " + compte);
            Thread.sleep((attente + 1) * 1000);
        }
        throw new IllegalStateException("connexion de " + compte + " impossible (429 persistant)");
    }

    static Rep deposer(String jeton, Path fichier, String nom, String typeId, String confidentialite) throws Exception {
        String frontiere = "----qa" + UUID.randomUUID();
        var buf = new java.io.ByteArrayOutputStream();
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("name", nom);
        champs.put("typeDocumentId", typeId);
        if (confidentialite != null) {
            champs.put("confidentialite", confidentialite);
        }
        for (var e : champs.entrySet()) {
            buf.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        buf.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + nom
                + ".pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        buf.writeBytes(Files.readAllBytes(fichier));
        buf.writeBytes(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return appel("POST", "/api/v1/documents", jeton, "multipart/form-data; boundary=" + frontiere, buf.toByteArray());
    }

    // ------------------------------------------------------------------ outils JSON

    static Set<String> ids(JsonNode tableau) {
        Set<String> r = new LinkedHashSet<>();
        for (JsonNode n : tableau) {
            r.add(n.path("id").asText());
        }
        return r;
    }

    /** Tous les documents visibles d'un appelant (pagination complète), filtrés par texte. */
    static List<JsonNode> documents(String jeton, String recherche) throws Exception {
        List<JsonNode> r = new ArrayList<>();
        for (int page = 0; page < 100; page++) {
            Rep rep = get("/api/v1/documents?size=200&page=" + page + "&search=" + enc(recherche), jeton);
            if (rep.code() != 200) {
                throw new IllegalStateException("liste des documents : HTTP " + rep.code());
            }
            JsonNode j = rep.json();
            j.path("content").forEach(r::add);
            if (page + 1 >= j.path("totalPages").asInt(1)) {
                break;
            }
        }
        return r;
    }

    static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static void arbre(JsonNode noeuds, Map<String, JsonNode> acc) {
        for (JsonNode n : noeuds) {
            acc.put(n.path("name").asText(), n);
            arbre(n.path("children"), acc);
        }
    }

    static Set<String> sousArbre(JsonNode racine) {
        Set<String> r = new HashSet<>();
        r.add(racine.path("id").asText());
        for (JsonNode c : racine.path("children")) {
            r.addAll(sousArbre(c));
        }
        return r;
    }

    /** Corps d'erreur normalisé : sans horodatage, chemin, trace ni identifiant demandé. */
    static String normaliser(Rep r, String id) {
        JsonNode j = r.json();
        if (j.isObject()) {
            ObjectNode o = ((ObjectNode) j).deepCopy();
            for (String k : List.of("timestamp", "horodatage", "instance", "path", "chemin", "traceId", "trace_id", "requestId")) {
                o.remove(k);
            }
            return o.toString().replace(id, "<ID>");
        }
        return (r.corps() == null ? "" : r.corps()).replace(id, "<ID>");
    }

    static String typeContenu(Rep r) {
        return r.entetes().firstValue("Content-Type").orElse("").replaceAll(";.*", "");
    }

    static long mediane(List<Long> v) {
        List<Long> t = new ArrayList<>(v);
        t.sort(null);
        return t.get(t.size() / 2);
    }

    // ------------------------------------------------------------------ programme

    public static void main(String[] args) throws Exception {
        out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        boolean reinitialiser = Arrays.asList(args).contains("--reinitialiser");
        url = env("GED_URL", null);
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        if (url == null || mdp == null) {
            System.err.println("ERREUR_EXECUTION|GED_URL et GED_RECETTE_MOT_DE_PASSE obligatoires");
            System.exit(2);
        }
        url = url.replaceAll("/+$", "");
        String cAdmin = env("GED_E3_ADMIN", "sbennani"), cDep = env("GED_E3_DEPOSANT", "kelfassi");
        String cTiers = env("GED_E3_TIERS", "yalaoui"), cSans = env("GED_E3_SANS_DROIT", "nidrissi");
        Path fichier = Path.of(env("GED_E3_FICHIER", "recette/donnees/pdf_texte_fr_facture.pdf"));
        String nA = env("GED_E3_NOEUD_A", "Comptabilité"), nAe = env("GED_E3_NOEUD_A_ENFANT", "2026");
        String nB = env("GED_E3_NOEUD_B", "Ressources Humaines"), nBe = env("GED_E3_NOEUD_B_ENFANT", "Contrats");
        String nC = env("GED_E3_NOEUD_C", "Projets");
        String marque = "QAE3" + Long.toString(System.currentTimeMillis(), 36);
        out.println("# cible " + url + " ; marqueur des témoins : " + marque);

        // ---------- préparation du jeu (Administrateur)
        String tAdmin = connecter(cAdmin, mdp);
        // Provisionne les comptes (première connexion) avant de leur attribuer des droits.
        String tDep = connecter(cDep, mdp), tTiers = connecter(cTiers, mdp), tSans = connecter(cSans, mdp);
        Map<String, String> idUtil = new TreeMap<>();
        Map<String, String> nomUtil = new TreeMap<>();
        for (JsonNode u : get("/api/v1/admin/utilisateurs", tAdmin).json()) {
            idUtil.put(u.path("identifiant").asText().toLowerCase(), u.path("id").asText());
            nomUtil.put(u.path("identifiant").asText().toLowerCase(), u.path("fullName").asText());
        }
        String uDep = idUtil.get(cDep.toLowerCase()), uTiers = idUtil.get(cTiers.toLowerCase()), uSans = idUtil.get(cSans.toLowerCase());
        Map<String, String> roles = new TreeMap<>();
        for (JsonNode r : get("/api/v1/admin/roles", tAdmin).json()) {
            roles.put(r.path("code").asText(), r.path("id").asText());
        }
        Map<String, JsonNode> noeuds = new LinkedHashMap<>();
        arbre(get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        for (String n : List.of(nA, nAe, nB, nBe, nC)) {
            if (!noeuds.containsKey(n)) {
                System.err.println("ERREUR_EXECUTION|nœud « " + n + " » introuvable dans l'arbre de l'Administrateur");
                System.exit(2);
            }
        }
        String A = noeuds.get(nA).path("id").asText(), Ae = noeuds.get(nAe).path("id").asText();
        String B = noeuds.get(nB).path("id").asText(), Be = noeuds.get(nBe).path("id").asText();
        String C = noeuds.get(nC).path("id").asText();
        Set<String> sousA = sousArbre(noeuds.get(nA));

        // Habilitations attendues : clé = sujet|rôle|nœud|rupture
        record Hab(String sujet, String role, String noeud, boolean rupture) {}
        List<Hab> attendues = List.of(
                new Hab(uDep, roles.get("DIRECTION_GENERALE"), null, false),
                new Hab(uTiers, roles.get("UTILISATEUR_STANDARD"), A, false),
                new Hab(uSans, roles.get("UTILISATEUR_STANDARD"), C, false),
                new Hab(uSans, roles.get("UTILISATEUR_STANDARD"), Be, false));
        List<String> etrangeres = new ArrayList<>();
        for (String u : List.of(uDep, uTiers, uSans)) {
            for (JsonNode h : get("/api/v1/admin/habilitations?sujetType=UTILISATEUR&sujetId=" + u, tAdmin).json()) {
                Hab vue = new Hab(u, h.path("roleId").isNull() ? null : h.path("roleId").asText(),
                        h.path("noeudId").isNull() ? null : h.path("noeudId").asText(), h.path("ruptureHeritage").asBoolean());
                if (!attendues.contains(vue)) {
                    if (reinitialiser) {
                        appel("DELETE", "/api/v1/admin/habilitations/" + h.path("id").asText(), tAdmin, null, null);
                    } else {
                        etrangeres.add(h.path("sujetLibelle").asText() + " → " + h.path("roleCode").asText() + " sur "
                                + h.path("noeudLibelle").asText("global") + (vue.rupture() ? " (rupture)" : ""));
                    }
                }
            }
        }
        if (!etrangeres.isEmpty()) {
            System.err.println("ERREUR_EXECUTION|habilitations hors jeu sur les comptes de test (relancer avec --reinitialiser) : " + etrangeres);
            System.exit(2);
        }
        for (Hab h : attendues) {
            boolean existe = false;
            for (JsonNode x : get("/api/v1/admin/habilitations?sujetType=UTILISATEUR&sujetId=" + h.sujet(), tAdmin).json()) {
                existe |= h.role().equals(x.path("roleId").asText()) && String.valueOf(h.noeud()).equals(x.path("noeudId").isNull() ? "null" : x.path("noeudId").asText());
            }
            if (!existe) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("sujetType", "UTILISATEUR");
                d.put("sujetId", h.sujet());
                d.put("roleId", h.role());
                d.put("noeudId", h.noeud());
                d.put("ruptureHeritage", false);
                Rep r = json("POST", "/api/v1/admin/habilitations", tAdmin, d);
                if (r.code() != 201) {
                    System.err.println("ERREUR_EXECUTION|habilitation refusée : HTTP " + r.code() + " " + r.corps());
                    System.exit(2);
                }
            }
        }
        // Aucun droit par groupe ne doit s'ajouter (jeu maîtrisé) : le TIERS ne consulte rien en B.
        JsonNode deTiersB = get("/api/v1/admin/droits-effectifs?utilisateurId=" + uTiers + "&noeudId=" + B, tAdmin).json();
        if (deTiersB.path("permissions").toString().contains("CONSULTER")) {
            System.err.println("ERREUR_EXECUTION|le TIERS consulte déjà le nœud B (groupe ?) : jeu non maîtrisé");
            System.exit(2);
        }

        // Type de document rangé dans B (créé au besoin : GERER_REFERENTIELS de l'Administrateur).
        String typeA = null, typeAe = null, typeB = null;
        for (JsonNode t : get("/api/v1/type-documents?size=200", tAdmin).json().path("content")) {
            String w = t.path("workspace").path("id").asText();
            if (w.equals(A) && typeA == null) typeA = t.path("id").asText();
            if (w.equals(Ae) && typeAe == null) typeAe = t.path("id").asText();
            if (w.equals(B) && typeB == null) typeB = t.path("id").asText();
        }
        if (typeB == null) {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("code", "TD-QA-RH");
            td.put("typeDeDocument", "Pièce RH (recette qa)");
            td.put("description", "Type créé par la recette E3 pour ranger un témoin dans le nœud B");
            td.put("workspaceId", B);
            td.put("typeAutorise", List.of("pdf"));
            td.put("tailleMaxMo", 10);
            Rep r = json("POST", "/api/v1/type-documents", tAdmin, td);
            typeB = r.json().path("id").asText(null);
        }
        if (typeA == null || typeAe == null || typeB == null) {
            System.err.println("ERREUR_EXECUTION|types de document introuvables (A=" + typeA + ", A/enfant=" + typeAe + ", B=" + typeB + ")");
            System.exit(2);
        }

        // Les droits viennent d'être posés : nouveaux jetons inutiles (droits relus à chaque requête).
        String[][] temoins = {{"D1", typeA, "PUBLIC"}, {"D2", typeA, "PRIVE"}, {"D3", typeA, "CONFIDENTIEL"},
                {"D4", typeAe, "PUBLIC"}, {"D5", typeB, "PUBLIC"}};
        Map<String, String> doc = new LinkedHashMap<>();
        Map<String, String> version = new LinkedHashMap<>();
        for (String[] t : temoins) {
            Rep r = deposer(tDep, fichier, marque + "-" + t[0] + "-" + t[2].toLowerCase(), t[1], t[2]);
            if (r.code() / 100 != 2) {
                System.err.println("ERREUR_EXECUTION|dépôt " + t[0] + " refusé : HTTP " + r.code() + " " + r.corps());
                System.exit(2);
            }
            doc.put(t[0], r.json().path("id").asText());
            version.put(t[0], r.json().path("versions").path(0).path("id").asText());
            String conf = r.json().path("confidentialite").asText();
            if (!t[2].equals(conf)) {
                res("E3-00-" + t[0], "ECHEC", "Niveau de confidentialité demandé au dépôt appliqué [12.3]", "demandé " + t[2] + ", obtenu " + conf);
            }
        }
        Rep r6 = deposer(tTiers, fichier, marque + "-D6-prive-tiers", typeA, "PRIVE");
        verif("E3-01", r6.code() / 100 == 2, "Le TIERS dépose dans son périmètre (Déposer hérité de A) [12.2]", "HTTP " + r6.code());
        doc.put("D6", r6.json().path("id").asText(""));
        version.put("D6", r6.json().path("versions").path(0).path("id").asText(""));
        out.println("# témoins : " + doc);

        // ---------- 1. Ce que voit chacun (liste, recherche, totaux)
        Set<String> vuDep = new HashSet<>(), vuTiers = new HashSet<>(), vuSans = new HashSet<>();
        for (JsonNode d : documents(tDep, marque)) vuDep.add(d.path("id").asText());
        List<JsonNode> listeTiers = documents(tTiers, marque);
        for (JsonNode d : listeTiers) vuTiers.add(d.path("id").asText());
        Rep listeSans = get("/api/v1/documents?size=200&search=" + marque, tSans);
        for (JsonNode d : listeSans.json().path("content")) vuSans.add(d.path("id").asText());
        verif("E3-02", vuDep.containsAll(doc.values()) && vuDep.size() == 6, "Témoin : la Direction Générale (accès global) voit les 6 témoins [12.2, D14]", vuDep.size() + "/6");
        Set<String> attenduTiers = Set.of(doc.get("D1"), doc.get("D4"), doc.get("D6"));
        verif("E3-03", vuTiers.equals(attenduTiers), "TIERS : exactement D1 (public A), D4 (public, hérité en A/enfant), D6 (son propre privé) [12.2, 12.3]",
                "vus " + noms(vuTiers, doc) + ", attendus D1 D4 D6");
        verif("E3-04", listeSans.code() == 200 && vuSans.isEmpty() && listeSans.json().path("total").asLong(-1) == 0,
                "SANS_DROIT : aucun témoin, total 0 dans la recherche [critère E3]", "HTTP " + listeSans.code() + ", total " + listeSans.json().path("total").asText());

        // Totaux non filtrés : le TIERS ne compte que A (public, ou privé dont il est déposant).
        List<JsonNode> tousDep = documents(tDep, "");
        String nomTiers = nomUtil.get(cTiers.toLowerCase());
        long attendu = tousDep.stream().filter(d -> sousA.contains(d.path("workspace").path("id").asText()))
                .filter(d -> d.path("confidentialite").asText().equals("PUBLIC")
                        || (d.path("confidentialite").asText().equals("PRIVE") && d.path("createdBy").asText().equals(nomTiers)))
                .count();
        Rep totTiers = get("/api/v1/documents?size=1", tTiers);
        long total = totTiers.json().path("total").asLong(-1);
        verif("E3-05", total == attendu, "TIERS : total de la liste = documents de son périmètre seulement [5.3.2, P5]",
                "total " + total + ", attendu " + attendu + " (calculé sur la vue de la Direction Générale)");
        Rep totSans = get("/api/v1/documents?size=1", tSans);
        verif("E3-06", totSans.code() == 200 && totSans.json().path("total").asLong(-1) == 0 && totSans.json().path("content").size() == 0,
                "SANS_DROIT : liste complète vide, total 0 [critère E3]", "HTTP " + totSans.code() + ", total " + totSans.json().path("total").asText());

        // Tableau de bord : tuiles et répartitions au périmètre
        for (Object[] p : new Object[][]{{"E3-07", tTiers, total, "TIERS"}, {"E3-08", tSans, 0L, "SANS_DROIT"}}) {
            String t = (String) p[1];
            long docs = get("/api/v1/stats/overview", t).json().path("documents").asLong(-1);
            long parType = 0;
            for (JsonNode x : get("/api/v1/stats/par-type", t).json()) parType += x.path("count").asLong(x.path("total").asLong(0));
            long depots = 0;
            for (JsonNode x : get("/api/v1/stats/depots?jours=3650", t).json()) depots += x.path("count").asLong(0);
            long att = (Long) p[2];
            verif((String) p[0], docs == att && parType == att && depots <= att,
                    p[3] + " : tableau de bord (tuile, répartition par type, courbe des dépôts) limité au périmètre [P5]",
                    "tuile " + docs + ", par type " + parType + ", dépôts " + depots + ", attendu " + att);
        }

        // Recherche par index (module indexation)
        Map<String, Object> rq = new LinkedHashMap<>();
        rq.put("typeDocumentId", typeA);
        Rep rech = json("POST", "/api/v1/indexation/recherche", tTiers, rq);
        Set<String> trouves = new HashSet<>();
        for (JsonNode g : rech.json()) for (JsonNode d : g.path("documents")) trouves.add(d.path("id").asText());
        verif("E3-09", rech.code() == 200 && trouves.contains(doc.get("D1")) && !trouves.contains(doc.get("D2")) && !trouves.contains(doc.get("D3")),
                "Recherche par index : filtrage par droits et confidentialité à la source [12.3, 6.4]", "HTTP " + rech.code() + ", témoins trouvés " + noms(trouves, doc));
        Rep rechSans = json("POST", "/api/v1/indexation/recherche", tSans, rq);
        int nbSans = 0;
        for (JsonNode g : rechSans.json()) nbSans += g.path("documents").size();
        verif("E3-10", (rechSans.code() == 200 && nbSans == 0) || rechSans.code() == 404 || rechSans.code() == 403,
                "Recherche par index du SANS_DROIT sur un type hors périmètre : aucun résultat", "HTTP " + rechSans.code() + ", " + nbSans + " document(s)");

        // ---------- 2. Arborescence
        Map<String, JsonNode> arbreTiers = new LinkedHashMap<>(), arbreSans = new LinkedHashMap<>();
        arbre(get("/api/v1/workspaces/tree", tTiers).json(), arbreTiers);
        arbre(get("/api/v1/workspaces/tree", tSans).json(), arbreSans);
        verif("E3-11", arbreTiers.containsKey(nA) && arbreTiers.containsKey(nAe) && !arbreTiers.containsKey(nB) && !arbreTiers.containsKey(nC),
                "Arbre du TIERS : A et sa descendance, ni B ni C [P5]", "nœuds " + arbreTiers.keySet());
        boolean passageB = arbreSans.containsKey(nB) && arbreSans.get(nB).path("passage").asBoolean(false);
        verif("E3-12", !arbreSans.containsKey(nA) && arbreSans.containsKey(nC) && arbreSans.containsKey(nBe) && passageB
                        && !arbreSans.get(nBe).path("passage").asBoolean(true),
                "Arbre du SANS_DROIT : C et B/enfant couverts, B affiché en simple passage, A absent [12.2.3 P5]",
                "nœuds " + arbreSans.keySet() + ", B en passage : " + passageB);
        Rep listeB = get("/api/v1/documents?size=200&workspaceId=" + B, tSans);
        verif("E3-13", (listeB.code() == 200 && listeB.json().path("total").asLong(-1) == 0) || listeB.code() == 404 || listeB.code() == 403,
                "Nœud de passage : aucun document ni total (D5 rangé en B reste invisible)", "HTTP " + listeB.code() + ", total " + listeB.json().path("total").asText());

        // ---------- 3. Accès direct : 404 indiscernable d'un identifiant inexistant
        String inconnu = UUID.randomUUID().toString();
        String vInconnue = UUID.randomUUID().toString();
        List<String[]> routes = List.of(
                new String[]{"GET", "/api/v1/documents/{id}"}, new String[]{"GET", "/api/v1/documents/{id}/download"},
                new String[]{"GET", "/api/v1/versions/{v}/apercu"}, new String[]{"GET", "/api/v1/documents/{id}/rattachements"},
                new String[]{"GET", "/api/v1/documents/{id}/designes"}, new String[]{"PUT", "/api/v1/documents/{id}"},
                new String[]{"PATCH", "/api/v1/documents/{id}/verrou?verrouille=true"}, new String[]{"DELETE", "/api/v1/documents/{id}"},
                new String[]{"PATCH", "/api/v1/documents/{id}/restore"}, new String[]{"POST", "/api/v1/documents/{id}/rattachements"},
                new String[]{"GET", "/api/v1/ocr/documents/{id}/texte"}, new String[]{"GET", "/api/v1/indexation/documents/{id}"},
                new String[]{"GET", "/api/v1/indexation/documents/{id}/champs"}, new String[]{"GET", "/api/v1/signatures/document/{id}"});
        List<String> ecarts = new ArrayList<>(), non404 = new ArrayList<>();
        int compares = 0;
        for (String[] cas : new String[][]{{"TIERS", tTiers, "D2"}, {"TIERS", tTiers, "D3"}, {"TIERS", tTiers, "D5"},
                {"SANS_DROIT", tSans, "D1"}, {"SANS_DROIT", tSans, "D5"}, {"SANS_DROIT", tSans, "D6"}}) {
            String cible = doc.get(cas[2]), v = version.get(cas[2]);
            for (String[] rt : routes) {
                String chCible = rt[1].replace("{id}", cible).replace("{v}", v);
                String chInconnu = rt[1].replace("{id}", inconnu).replace("{v}", vInconnue);
                byte[] corps = rt[0].equals("GET") || rt[0].equals("DELETE") ? null
                        : rt[1].contains("rattachements") ? JSON.writeValueAsBytes(Map.of("noeudId", C)) : "{}".getBytes();
                Rep a = appel(rt[0], chCible, cas[1], corps == null ? null : "application/json", corps);
                Rep b = appel(rt[0], chInconnu, cas[1], corps == null ? null : "application/json", corps);
                compares++;
                String sa = a.code() + " " + typeContenu(a) + " " + normaliser(a, cible).replace(v, "<ID>");
                String sb = b.code() + " " + typeContenu(b) + " " + normaliser(b, inconnu).replace(vInconnue, "<ID>");
                if (a.code() != 404) non404.add(cas[0] + " " + cas[2] + " " + rt[0] + " " + rt[1] + " → " + a.code());
                if (!sa.equals(sb)) ecarts.add(cas[0] + " " + cas[2] + " " + rt[0] + " " + rt[1] + " : [" + trunc(sa) + "] ≠ inexistant [" + trunc(sb) + "]");
            }
        }
        verif("E3-14", non404.isEmpty(), "Objet hors périmètre : 404 sur les " + routes.size() + " routes documentaires, pour chaque témoin interdit [6.2.3 A01]",
                non404.isEmpty() ? compares + " appels" : non404.size() + " écart(s) : " + String.join(" ; ", non404.subList(0, Math.min(6, non404.size()))));
        verif("E3-15", ecarts.isEmpty(), "404 indiscernable d'un identifiant inexistant : même statut, même type de contenu, même corps (hors horodatage) [6.2.3 A01, P5]",
                ecarts.isEmpty() ? compares + " comparaisons" : ecarts.size() + " écart(s) : " + String.join(" ; ", ecarts.subList(0, Math.min(4, ecarts.size()))));
        // Les témoins interdits n'ont pas été modifiés par les tentatives d'écriture
        Rep verifD1 = get("/api/v1/documents/" + doc.get("D1"), tDep);
        verif("E3-16", verifD1.code() == 200 && !verifD1.json().path("supprime").asBoolean(false) && !verifD1.json().path("deleted").asBoolean(false)
                        && !verifD1.json().path("verrouille").asBoolean(false),
                "Aucune écriture hors périmètre n'a pris effet (D1 ni supprimé ni verrouillé par le SANS_DROIT)", "HTTP " + verifD1.code());

        // Temps de réponse : indice de fuite (le 404 ne doit pas être mesurablement plus lent ou plus rapide)
        List<Long> tInterdit = new ArrayList<>(), tAbsent = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tInterdit.add(get("/api/v1/documents/" + doc.get("D5"), tTiers).nanos());
            tAbsent.add(get("/api/v1/documents/" + UUID.randomUUID(), tTiers).nanos());
        }
        double m1 = mediane(tInterdit) / 1e6, m2 = mediane(tAbsent) / 1e6, ratio = Math.max(m1, m2) / Math.max(0.001, Math.min(m1, m2));
        res("E3-17", ratio < 2.0 ? "OK" : "AVERT", "Temps de réponse du 404 : hors périmètre et inexistant comparables (médiane sur 25 appels)",
                String.format("%.1f ms / %.1f ms, rapport %.2f", m1, m2, ratio));

        // 403 sur un objet visible sans la permission (le TIERS n'a pas Supprimer)
        Rep supp = appel("DELETE", "/api/v1/documents/" + doc.get("D1"), tTiers, null, null);
        Rep encore = get("/api/v1/documents/" + doc.get("D1"), tTiers);
        verif("E3-18", supp.code() == 403 && encore.code() == 200, "Objet visible sans la permission : 403, rien de supprimé [6.2.3 A01]",
                "DELETE " + supp.code() + ", relecture " + encore.code());

        // ---------- 4. Confidentialité : désignation, intersection avec l'emplacement
        String d3 = doc.get("D3");
        Rep des = json("POST", "/api/v1/documents/" + d3 + "/designes", tDep, Map.of("utilisateurId", uTiers));
        Rep vu = get("/api/v1/documents/" + d3, tTiers);
        boolean dansListe = ids(get("/api/v1/documents?size=200&search=" + marque, tTiers).json().path("content")).contains(d3);
        verif("E3-19", des.code() / 100 == 2 && vu.code() == 200 && dansListe,
                "CONFIDENTIEL : visible du TIERS dès sa désignation (fiche et recherche) [12.3]", "désignation " + des.code() + ", fiche " + vu.code() + ", recherche " + dansListe);
        Rep ret = appel("DELETE", "/api/v1/documents/" + d3 + "/designes/" + uTiers, tDep, null, null);
        Rep plus = get("/api/v1/documents/" + d3, tTiers);
        verif("E3-20", ret.code() / 100 == 2 && plus.code() == 404, "Retrait de la désignation : effet immédiat (404) [12.3]", "retrait " + ret.code() + ", fiche " + plus.code());
        Rep des2 = json("POST", "/api/v1/documents/" + d3 + "/designes", tDep, Map.of("utilisateurId", uSans));
        Rep vuSansConf = get("/api/v1/documents/" + d3, tSans);
        verif("E3-21", vuSansConf.code() == 404, "Désigné SANS droit sur l'emplacement : toujours 404 (intersection) [12.3]", "désignation " + des2.code() + ", fiche " + vuSansConf.code());
        appel("DELETE", "/api/v1/documents/" + d3 + "/designes/" + uSans, tDep, null, null);
        Rep prive = get("/api/v1/documents/" + doc.get("D2"), tTiers);
        Rep propre = get("/api/v1/documents/" + doc.get("D6"), tTiers);
        verif("E3-22", prive.code() == 404 && propre.code() == 200, "PRIVE : invisible d'un tiers, visible de son déposant [12.3]", "D2 (autrui) " + prive.code() + ", D6 (le sien) " + propre.code());

        // ---------- 5. Rupture d'héritage, attribution et retrait immédiats
        Map<String, Object> rup = new LinkedHashMap<>();
        rup.put("sujetType", "UTILISATEUR");
        rup.put("sujetId", uTiers);
        rup.put("roleId", null);
        rup.put("noeudId", Ae);
        rup.put("ruptureHeritage", true);
        Rep rupture = json("POST", "/api/v1/admin/habilitations", tAdmin, rup);
        Rep d4 = get("/api/v1/documents/" + doc.get("D4"), tTiers);
        Map<String, JsonNode> arbreRup = new LinkedHashMap<>();
        arbre(get("/api/v1/workspaces/tree", tTiers).json(), arbreRup);
        verif("E3-23", rupture.code() == 201 && d4.code() == 404 && !arbreRup.containsKey(nAe),
                "Rupture d'héritage sans attribution sur A/enfant : D4 et le nœud disparaissent aussitôt [12.2, D14]",
                "habilitation " + rupture.code() + ", D4 " + d4.code() + ", nœud encore dans l'arbre : " + arbreRup.containsKey(nAe));
        String idRup = rupture.json().path("id").asText("");
        appel("DELETE", "/api/v1/admin/habilitations/" + idRup, tAdmin, null, null);
        Rep d4b = get("/api/v1/documents/" + doc.get("D4"), tTiers);
        verif("E3-24", d4b.code() == 200, "Retrait de la rupture : effet immédiat, D4 de nouveau visible [12.2]", "D4 " + d4b.code());

        // ---------- 6. Rattachement : union des emplacements, une seule ligne
        String d5 = doc.get("D5");
        Rep rat = json("POST", "/api/v1/documents/" + d5 + "/rattachements", tDep, Map.of("noeudId", A));
        Rep d5vu = get("/api/v1/documents/" + d5, tTiers);
        long occurrences = documents(tTiers, marque).stream().filter(d -> d.path("id").asText().equals(d5)).count();
        verif("E3-25", rat.code() / 100 == 2 && d5vu.code() == 200 && occurrences == 1,
                "Rattachement à A : D5 visible du TIERS (union des emplacements), une seule ligne en recherche [12.4]",
                "rattachement " + rat.code() + ", fiche " + d5vu.code() + ", lignes " + occurrences);
        Rep det = appel("DELETE", "/api/v1/documents/" + d5 + "/rattachements/" + A, tDep, null, null);
        Rep d5plus = get("/api/v1/documents/" + d5, tTiers);
        verif("E3-26", det.code() / 100 == 2 && d5plus.code() == 404, "Retrait du rattachement : D5 de nouveau hors périmètre (404) [12.4]",
                "retrait " + det.code() + ", fiche " + d5plus.code());

        // ---------- 7. Administration des droits
        Rep deD1 = get("/api/v1/admin/droits-effectifs?utilisateurId=" + uTiers + "&documentId=" + doc.get("D1"), tAdmin);
        Rep deD5 = get("/api/v1/admin/droits-effectifs?utilisateurId=" + uTiers + "&documentId=" + d5, tAdmin);
        // Pour un document, les origines sont données par emplacement (principal, rattachements).
        String origineD1 = deD1.json().path("emplacements").path(0).path("origines").path(0).path("nature").asText("");
        Rep deAe = get("/api/v1/admin/droits-effectifs?utilisateurId=" + uTiers + "&noeudId=" + Ae, tAdmin);
        String origineAe = deAe.json().path("origines").path(0).path("nature").asText("");
        verif("E3-27", deD1.code() == 200 && deD1.json().path("permissions").toString().contains("CONSULTER")
                        && origineD1.equals("ATTRIBUTION_DIRECTE") && origineAe.equals("HERITAGE")
                        && !deD5.json().path("permissions").toString().contains("CONSULTER"),
                "Droits effectifs consultables avec leur origine ; cohérents avec les accès constatés [12.2.3]",
                "D1 " + deD1.json().path("permissions") + " origine " + origineD1 + " ; A/enfant origine " + origineAe
                        + " ; D5 " + deD5.json().path("permissions"));
        Rep adm = get("/api/v1/admin/habilitations", tTiers);
        Rep admSans = get("/api/v1/admin/droits-effectifs?utilisateurId=" + uTiers, tSans);
        verif("E3-28", adm.code() == 403 && admSans.code() == 403, "Administration des droits refusée hors Administrateur (403) [6.4]",
                "TIERS " + adm.code() + ", SANS_DROIT " + admSans.code());

        out.println("# témoins conservés (marqueur " + marque + ") ; habilitations du jeu conservées");
        out.println("BILAN|E3 autorisation " + url + "|ok=" + ok + "|echec=" + echec + "|avert=" + avert + "|na=" + na);
        System.exit(echec == 0 ? 0 : 1);
    }

    static String trunc(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    static String noms(Collection<String> ids, Map<String, String> doc) {
        List<String> r = new ArrayList<>();
        for (var e : doc.entrySet()) if (ids.contains(e.getValue())) r.add(e.getKey());
        return r.isEmpty() ? "aucun" : String.join(" ", r);
    }
}
