import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Recette qa, vague 8 : parties NON EXERCÉES des lignes « Vérifié » de SUIVI.md, contre une instance.
 *
 * <ul>
 *   <li>T-031 : langue OCR réglée par type de document ({@code ged.ocr.chaine.langues-par-type[QAV8FRA]=fra},
 *       {@code [QAV8ARA]=ara}), le même scan arabe déposé sous trois types ;</li>
 *   <li>T-034 / P-09 : PDF tronqué → reprises, {@code OCR_ECHEC} avec motif, document téléchargeable et
 *       « non interrogeable », écran de supervision réservé, relance manuelle ;</li>
 *   <li>T-039 : réindexation complète réservée à l'Administrateur, recherche disponible pendant ;</li>
 *   <li>T-097 : export ZIP d'un dossier contenant un document rattaché (présent une fois, tous ses chemins) ;</li>
 *   <li>P-22 : modification des droits auditée avant / après ;</li>
 *   <li>T-018 : cache annuaire de 15 minutes, relu à expiration (annuaire simulé modifié par LDAP).</li>
 * </ul>
 * Variables : GED_URL, GED_RECETTE_MOT_DE_PASSE, GED_DONNEES, GED_V8_JDBC (base de l'instance, propriétaire),
 * GED_V8_LDAP_PORT (annuaire simulé de l'instance), GED_V8_ATTENTE_S (défaut 900).
 * Usage : lancer-java.sh recette/e10/RecetteComplementsV8.java RecetteComplementsV8
 */
public class RecetteComplementsV8 extends ClientGed {

    RecetteComplementsV8(String url) {
        super(url);
    }

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static void arbre(JsonNode n, Map<String, JsonNode> m) {
        if (n.isArray()) { for (JsonNode x : n) arbre(x, m); return; }
        m.putIfAbsent(n.path("name").asText(), n);
        arbre(n.path("children"), m);
    }

    public static void main(String[] args) throws Exception {
        RecetteComplementsV8 g = new RecetteComplementsV8(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        String jdbc = env("GED_V8_JDBC", null);
        int attente = Integer.parseInt(env("GED_V8_ATTENTE_S", "900"));
        String tAdmin = g.connecter("sbennani", mdp);
        String tDep = g.connecter("kelfassi", mdp);
        String tTiers = g.connecter("yalaoui", mdp);
        String marque = "QAV8" + Long.toString(System.currentTimeMillis(), 36);

        Map<String, JsonNode> noeuds = new LinkedHashMap<>();
        arbre(g.get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        String A = noeuds.get("Comptabilité").path("id").asText(), Ae = noeuds.get("2026").path("id").asText();
        Map<String, String> types = new LinkedHashMap<>();
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content")) types.put(t.path("code").asText(), t.path("id").asText());
        for (String code : List.of("QAV8FRA", "QAV8ARA")) {
            if (types.containsKey(code)) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("code", code);
            t.put("typeDeDocument", "Recette v8 langue " + code.substring(4));
            t.put("description", "v8");
            t.put("workspaceId", A);
            t.put("typeAutorise", List.of("pdf"));
            t.put("tailleMaxMo", 20);
            types.put(code, id(g.json("POST", "/api/v1/type-documents", tAdmin, t)));
        }

        // ============================================================ T-031 langue par type
        byte[] scanAr = Files.readAllBytes(donnees.resolve("scan_ar_courrier.pdf"));
        Map<String, String> docs = new LinkedHashMap<>();
        for (String code : List.of("QAV8FRA", "QAV8ARA", "TD-FACT"))
            docs.put(code, id(g.deposer(tDep, scanAr, marque + "-" + code + ".pdf", "application/pdf",
                    Map.of("name", marque + "-langue-" + code, "typeDocumentId", types.get(code)), null)));

        // ============================================================ T-034 / P-09 : PDF tronqué
        byte[] scanFr = Files.readAllBytes(donnees.resolve("scan_fr_courrier.pdf"));
        byte[] tronque = Arrays.copyOf(scanFr, Math.min(4000, scanFr.length));
        Rep depT = g.deposer(tDep, tronque, marque + "-tronque.pdf", "application/pdf",
                Map.of("name", marque + "-tronque", "typeDocumentId", types.get("TD-FACT")), null);
        String dT = depT.json().path("id").asText();
        info("dépôt du PDF tronqué : HTTP " + depT.code() + " " + depT.json().path("statutOcr").asText());

        // Attente de l'OCR des trois scans et de l'échec du tronqué.
        Map<String, JsonNode> textes = new LinkedHashMap<>();
        JsonNode jobEchec = null;
        long debut = System.currentTimeMillis(), fin = debut + attente * 1000L;
        while ((textes.size() < docs.size() || jobEchec == null) && System.currentTimeMillis() < fin) {
            for (var e : docs.entrySet()) {
                if (textes.containsKey(e.getKey())) continue;
                JsonNode t = g.get("/api/v1/ocr/documents/" + e.getValue() + "/texte", tDep).json();
                if (t.path("interrogeable").asBoolean() || t.path("statutOcr").asText().equals("OCR_ECHEC")) textes.put(e.getKey(), t);
            }
            if (jobEchec == null && !dT.isEmpty()) {
                for (JsonNode j : g.get("/api/v1/admin/ocr/jobs?statut=OCR_ECHEC", tAdmin).json())
                    if (j.path("documentId").asText().equals(dT)) jobEchec = j;
            }
            Thread.sleep(5000);
        }
        info("attente OCR : " + (System.currentTimeMillis() - debut) / 1000 + " s");
        List<String> langues = new ArrayList<>();
        for (String code : docs.keySet()) langues.add(code + "=" + textes.getOrDefault(code, JSON.createObjectNode()).path("langue").asText("?"));
        verif("T031-01", langues.equals(List.of("QAV8FRA=fra", "QAV8ARA=ara", "TD-FACT=ara+fra")),
                "Langue OCR réglée par type de document (configuration par code de type), défaut ara+fra ailleurs [4.3.4, T-031]",
                String.join(", ", langues));
        Rep rAr = g.get("/api/v1/recherche/plein-texte?taille=50&q=" + enc("زركولين"), tDep);
        List<String> trouves = new ArrayList<>();
        for (var e : docs.entrySet()) if (rAr.corps().contains(e.getValue())) trouves.add(e.getKey());
        res("T031-02", trouves.contains("QAV8ARA") && trouves.contains("TD-FACT") ? "OK" : "ECHEC",
                "Témoin arabe « زركولين » trouvé sous ara et ara+fra (le réglage fra seul est un choix d'exploitation) [4.3.4]",
                "trouvé pour " + trouves + " (sur " + docs.keySet() + ")");

        // T-034 / P-09
        if (jobEchec == null) {
            res("T034-01", "ECHEC", "PDF illisible : OCR_ECHEC après les reprises", "aucun job en OCR_ECHEC pour " + dT + " après " + attente + " s (dépôt HTTP " + depT.code() + ")");
        } else {
            JsonNode fiche = g.get("/api/v1/documents/" + dT, tDep).json();
            JsonNode tx = g.get("/api/v1/ocr/documents/" + dT + "/texte", tDep).json();
            Rep dl = g.get("/api/v1/documents/" + dT + "/download", tDep);
            // Un fichier corrompu ne se répare pas en réessayant : un échec définitif immédiat est accepté
            // (AVERT, à confirmer par la revue) ; les reprises sont éprouvées sur un échec transitoire (délai).
            boolean base = !jobEchec.path("motifEchec").asText().isBlank() && dl.code() == 200 && !tx.path("interrogeable").asBoolean(true);
            res("T034-01", !base ? "ECHEC" : jobEchec.path("tentatives").asInt() >= 3 ? "OK" : "AVERT",
                    "PDF illisible : OCR_ECHEC avec motif, document téléchargeable et signalé non interrogeable ; 3 tentatives [4.3.4, T-034]",
                    "tentatives " + jobEchec.path("tentatives") + ", motif « " + jobEchec.path("motifEchec").asText() + " », statut fiche "
                            + fiche.path("statutOcr").asText() + ", texte " + tx.path("statutOcr").asText() + " interrogeable=" + tx.path("interrogeable").asText()
                            + ", téléchargement " + dl.code());
            Rep supT = g.get("/api/v1/admin/ocr/jobs?statut=OCR_ECHEC", tTiers);
            Rep relT = g.appel("POST", "/api/v1/admin/ocr/jobs/" + jobEchec.path("id").asText() + "/relance", tTiers, null, null);
            Rep rel = g.appel("POST", "/api/v1/admin/ocr/jobs/" + jobEchec.path("id").asText() + "/relance", tAdmin, null, null);
            String apres = "?";
            int tentApres = -1;
            for (String st : List.of("EN_ATTENTE_OCR", "EN_COURS_OCR", "OCR_ECHEC", "OCR_TERMINE"))
                for (JsonNode j : g.get("/api/v1/admin/ocr/jobs?statut=" + st, tAdmin).json())
                    if (j.path("id").asText().equals(jobEchec.path("id").asText())) { apres = st; tentApres = j.path("tentatives").asInt(); }
            JsonNode compteurs = g.get("/api/v1/admin/ocr/compteurs", tAdmin).json();
            verif("P09-01", supT.code() == 403 && relT.code() == 403 && rel.code() / 100 == 2 && (apres.equals("EN_ATTENTE_OCR") || apres.equals("EN_COURS_OCR")),
                    "Supervision OCR : liste des échecs avec motif réservée à l'Administrateur, relance manuelle remet le job en file [4.3.4, P-09]",
                    "tiers : liste " + supT.code() + ", relance " + relT.code() + " ; Administrateur : relance " + rel.code() + " → " + apres
                            + " (tentatives " + tentApres + "), compteurs " + compteurs);
        }

        // ============================================================ T-039 réindexation complète
        Rep avantR = g.get("/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", tDep);
        Rep reT = g.appel("POST", "/api/v1/admin/recherche/reindexation", tTiers, null, null);
        Rep re = g.appel("POST", "/api/v1/admin/recherche/reindexation", tAdmin, null, null);
        List<String> pendant = new ArrayList<>();
        JsonNode prog = re.json();
        long finR = System.currentTimeMillis() + 300_000;
        while (!prog.path("etat").asText().matches("TERMINEE|ECHEC|ERREUR") && System.currentTimeMillis() < finR) {
            pendant.add(prog.path("etat").asText() + " " + prog.path("traites").asText() + "/" + prog.path("total").asText()
                    + " recherche " + g.get("/api/v1/recherche/plein-texte?taille=5&q=zarkolinet", tDep).code());
            Thread.sleep(300);
            prog = g.get("/api/v1/admin/recherche/reindexation", tAdmin).json();
        }
        Rep apresR = g.get("/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", tDep);
        long nAvant = avantR.json().path("total").asLong(-1), nApres = apresR.json().path("total").asLong(-2);
        verif("T039-01", reT.code() == 403 && re.code() / 100 == 2 && prog.path("etat").asText().equals("TERMINEE") && nAvant == nApres && nAvant > 0,
                "Réindexation complète réservée à l'Administrateur, avec progression ; recherche disponible pendant ; résultats identiques après [4.4.1, T-039]",
                "tiers " + reT.code() + ", Administrateur " + re.code() + ", fin " + prog + ", relevés pendant " + (pendant.size() > 4 ? pendant.subList(0, 4) + "…(" + pendant.size() + ")" : pendant)
                        + ", zarkolinet " + nAvant + " → " + nApres);

        // ============================================================ T-097 export d'un rattaché
        byte[] pdf = Files.readAllBytes(donnees.resolve("pdf_texte_fr_convention.pdf"));
        String dR = id(g.deposer(tDep, pdf, marque + "-rattache.pdf", "application/pdf",
                Map.of("name", marque + "-rattache", "typeDocumentId", types.get("TD-FACT")), null));
        Rep rat = g.json("POST", "/api/v1/documents/" + dR + "/rattachements", tDep, Map.of("noeudId", Ae));
        Rep zip = g.appel("POST", "/api/v1/exports/dossiers/" + A, tDep, null, null);
        int fichiers = 0;
        String ligne = "";
        List<String> noms = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.octets()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                byte[] b = z.readAllBytes();
                if (e.getName().endsWith("manifeste.csv")) {
                    for (String l : new String(b, StandardCharsets.UTF_8).split("\r?\n")) if (l.startsWith(dR) || l.contains(dR)) ligne = l;
                } else if (e.getName().contains(marque + "-rattache")) { fichiers++; noms.add(e.getName()); }
            }
        }
        verif("T097-01", rat.code() == 201 && zip.code() == 200 && fichiers == 1 && ligne.contains("Comptabilité") && ligne.contains("2026"),
                "Export ZIP d'un dossier contenant un document rattaché à un sous-dossier : fichier présent une seule fois, tous ses chemins au manifeste [12.4, 12.10, T-097]",
                "rattachement " + rat.code() + ", export " + zip.code() + ", fichiers " + fichiers + " " + noms + ", manifeste « " + (ligne.length() > 220 ? ligne.substring(0, 220) + "…" : ligne) + " »");

        // ============================================================ P-22 audit des droits
        Map<String, String> roles = new LinkedHashMap<>();
        for (JsonNode r : g.get("/api/v1/admin/roles", tAdmin).json()) roles.put(r.path("code").asText(), r.path("id").asText());
        String uSans = null;
        for (JsonNode u : g.get("/api/v1/admin/utilisateurs", tAdmin).json()) if (u.path("identifiant").asText().equalsIgnoreCase("nidrissi")) uSans = u.path("id").asText();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("sujetType", "UTILISATEUR");
        h.put("sujetId", uSans);
        h.put("roleId", roles.get("UTILISATEUR_STANDARD"));
        h.put("noeudId", Ae);
        h.put("ruptureHeritage", false);
        Rep pose = g.json("POST", "/api/v1/admin/habilitations", tAdmin, h);
        String hId = pose.json().path("id").asText();
        Rep retrait = g.appel("DELETE", "/api/v1/admin/habilitations/" + hId, tAdmin, null, null);
        Thread.sleep(500);
        JsonNode ev = g.get("/api/v1/audit/evenements?action=HABILITATION_MODIFIEE&size=50", tAdmin).json();
        List<String> vus = new ArrayList<>();
        boolean creation = false, suppression = false;
        for (JsonNode x : ev.path("content").isMissingNode() ? ev : ev.path("content")) {
            String s = x.toString();
            if (!s.contains(hId)) continue;
            boolean av = !x.path("avant").isNull() && !x.path("avant").isMissingNode(), ap = !x.path("apres").isNull() && !x.path("apres").isMissingNode();
            if (!av && ap) creation = true;
            if (av && !ap) suppression = true;
            vus.add("avant=" + (av ? "oui" : "non") + " après=" + (ap ? "oui" : "non") + " acteur=" + x.path("acteurNom").asText());
        }
        verif("P22-01", pose.code() == 201 && retrait.code() == 204 && creation && suppression,
                "Modification des droits auditée : attribution (après seul) et retrait (avant seul), acteur Administrateur [12.2.3, 7.4.1, P-22]",
                "pose " + pose.code() + ", retrait " + retrait.code() + ", événements " + vus);

        // ============================================================ T-018 cache annuaire 15 min
        if (jdbc == null) {
            res("T018-01", "NA", "Cache annuaire relu à expiration", "GED_V8_JDBC absent");
        } else {
            int port = Integer.parseInt(env("GED_V8_LDAP_PORT", "33394"));
            String dn;
            try (LDAPConnection c = new LDAPConnection("localhost", port, "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma", mdp);
                 Connection db = DriverManager.getConnection(jdbc)) {
                dn = c.search("DC=marchicamed,DC=ma", SearchScope.SUB, "(sAMAccountName=kelfassi)").getSearchEntries().get(0).getDN();
                String d0 = g.get("/api/v1/auth/me", tDep).json().path("direction").asText();
                long duree;
                try (PreparedStatement p = db.prepareStatement("SELECT extract(epoch FROM expire_le - lu_le) FROM ged.cache_annuaire WHERE identifiant = 'kelfassi'");
                     ResultSet rs = p.executeQuery()) { rs.next(); duree = Math.round(rs.getDouble(1)); }
                c.modify(dn, new Modification(ModificationType.REPLACE, "department", "Direction QA v8"));
                String d1 = g.get("/api/v1/auth/me", tDep).json().path("direction").asText();
                try (PreparedStatement p = db.prepareStatement("UPDATE ged.cache_annuaire SET lu_le = now() - interval '16 minutes', expire_le = now() - interval '1 minute' WHERE identifiant = 'kelfassi'")) { p.executeUpdate(); }
                String d2 = g.get("/api/v1/auth/me", tDep).json().path("direction").asText();
                c.modify(dn, new Modification(ModificationType.REPLACE, "department", d0));
                try (PreparedStatement p = db.prepareStatement("UPDATE ged.cache_annuaire SET lu_le = now() - interval '16 minutes', expire_le = now() - interval '1 minute' WHERE identifiant = 'kelfassi'")) { p.executeUpdate(); }
                String d3 = g.get("/api/v1/auth/me", tDep).json().path("direction").asText();
                verif("T018-01", duree == 900 && d1.equals(d0) && d2.equals("Direction QA v8") && d3.equals(d0),
                        "Cache annuaire de 15 minutes : valeur gardée tant qu'elle est valide, relue dans l'annuaire à expiration [3.4.2, T-018, D1]",
                        "durée " + duree + " s ; direction « " + d0 + " » ; annuaire modifié, cache valide → « " + d1 + " » ; cache expiré → « " + d2 + " » ; remis → « " + d3 + " »");
            }
        }
        System.exit(bilan("Compléments vague 8 " + g.url));
    }
}
