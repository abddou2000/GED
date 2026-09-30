import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Recette technique, tour 2 (qa) : revérification des anomalies corrigées au tour 1 au-delà du
 * scénario d'origine, et recette des lignes livrées au tour 1.
 * <ul>
 *   <li>ANO-E7-004 (P-21, §12.7) : tri {@code DATE_DOCUMENT} du contrat ; document déposé sans date (la date
 *       du dépôt lui est donnée, colonne NOT NULL) ;</li>
 *   <li>ANO-E7-005 (D10, §12.6) : création de dossier par l'Administrateur, déplacement de dossier
 *       (PATCH parent et fiche), déplacement et rattachement de document, restauration depuis la
 *       corbeille d'un dossier et d'un document sous un dossier archivé ;</li>
 *   <li>T-059 (§6.1.4, E5-A08) : vérification d'intégrité à la demande, d'un document (sain puis
 *       altéré dans le coffre, puis restauré) et du fonds, réservée et tracée ;</li>
 *   <li>T-055 / D15 (§5.5) : délégation refusée pour un compte désactivé APRÈS coup dans l'annuaire,
 *       fenêtre bornée par le cache ({@code ged.api.delegation.cache-etat-compte}, 2 min).</li>
 * </ul>
 * Lecture seule en base (JDBC {@code GED_R2_JDBC}) ; l'altération porte sur le coffre de l'instance
 * de qa ({@code GED_STOCKAGE_RACINE}), avec copie et restauration du fichier.
 * Modes : sans argument, tout ; sinon une liste parmi {@code e7004 e7005 t059 d15}.
 */
public class RecetteTour2 extends ClientGed {

    RecetteTour2(String url) {
        super(url);
    }

    static RecetteTour2 g;
    static String tAdmin, eAdmin, mdp, m;
    static byte[] pdf, pdf2;
    static Connection db;

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static String espace(String nom, String code, String parent) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", nom);
        e.put("code", code);
        e.put("employeId", eAdmin);
        if (parent != null) e.put("parentId", parent);
        return id(g.json("POST", "/api/v1/workspaces", tAdmin, e));
    }

    static String type(String code, String espace) throws Exception {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("typeDeDocument", code);
        t.put("description", "recette qa tour 2");
        t.put("workspaceId", espace);
        t.put("typeAutorise", List.of("pdf"));
        t.put("tailleMaxMo", 5);
        t.put("confidentialiteDefaut", "PUBLIC");
        return id(g.json("POST", "/api/v1/type-documents", tAdmin, t));
    }

    static Rep deposer(String jeton, String type, String nom, String dateDocument) throws Exception {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("name", nom);
        c.put("typeDocumentId", type);
        if (dateDocument != null) c.put("dateDocument", dateDocument);
        return g.deposer(jeton, pdf, nom + ".pdf", "application/pdf", c, null);
    }

    static String sql1(String requete, Object... p) throws Exception {
        try (PreparedStatement s = db.prepareStatement(requete)) {
            for (int i = 0; i < p.length; i++) s.setObject(i + 1, p[i]);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static String ordre(List<String> ids, Map<String, String> noms) {
        List<String> r = new ArrayList<>();
        for (String i : ids) if (noms.containsKey(i)) r.add(noms.get(i));
        return r.toString();
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteTour2(env("GED_URL", "http://localhost:18084"));
        mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        pdf = Files.readAllBytes(donnees.resolve("pdf_texte_fr_convention.pdf"));
        pdf2 = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        eAdmin = g.get("/api/v1/auth/me", tAdmin).json().path("employeId").asText();
        m = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        String jdbc = env("GED_R2_JDBC", null);
        if (jdbc != null) db = DriverManager.getConnection(jdbc);
        List<String> modes = args.length == 0 ? List.of("e7004", "e7005", "t059", "d15") : List.of(args);
        if (modes.contains("e7004")) e7004();
        if (modes.contains("e7005")) e7005();
        if (modes.contains("t059")) t059();
        if (modes.contains("d15")) d15();
        System.exit(bilan("Recette technique tour 2 " + g.url));
    }

    // ============================================================== ANO-E7-004 : compléments
    static void e7004() throws Exception {
        String S = espace("QA-R2-tri-" + m, "QAR2T-" + m, null);
        String T = type("QAR2T-" + m, S);
        String a = id(deposer(tAdmin, T, "qar2-" + m + "-a", "2026-02-01"));
        String b = id(deposer(tAdmin, T, "qar2-" + m + "-b", "2026-05-01"));
        String c = id(deposer(tAdmin, T, "qar2-" + m + "-c", "2026-03-01"));
        String d = id(deposer(tAdmin, T, "qar2-" + m + "-d-sans-date", null));
        Map<String, String> noms = Map.of(a, "a", b, "b", c, "c", d, "d(date du dépôt)");
        String dateD = g.get("/api/v1/documents/" + d, tAdmin).json().path("dateDocument").asText();
        List<String> liste = new ArrayList<>(), contrat = new ArrayList<>(), contratTri = new ArrayList<>(), meta = new ArrayList<>();
        for (JsonNode x : g.get("/api/v1/documents?workspaceId=" + S + "&size=50", tAdmin).json().path("content")) liste.add(x.path("id").asText());
        for (JsonNode x : g.json("POST", "/api/v1/recherches", tAdmin, Map.of("typeDocumentId", T)).json().path("resultats")) contrat.add(x.path("documentId").asText());
        Rep rt = g.json("POST", "/api/v1/recherches", tAdmin, Map.of("typeDocumentId", T, "tri", "DATE_DOCUMENT"));
        for (JsonNode x : rt.json().path("resultats")) contratTri.add(x.path("documentId").asText());
        for (JsonNode x : g.json("POST", "/api/v1/documents/recherche", tAdmin, Map.of("typeDocumentId", T)).json().path("content")) meta.add(x.path("id").asText());
        List<String> attendu = List.of(d, b, c, a);   // d : date du dépôt (aujourd'hui), la plus récente
        verif("E7004-R2-01", rt.code() == 200 && contratTri.equals(attendu),
                "POST /recherches avec tri=DATE_DOCUMENT (valeur ajoutée au contrat) : date du document décroissante ; un dépôt sans date reçoit la date du dépôt [P-21, 12.7]",
                "HTTP " + rt.code() + " " + ordre(contratTri, noms) + ", date donnée à d : " + dateD);
        verif("E7004-R2-02", contrat.equals(attendu) && meta.equals(attendu),
                "POST /recherches sans tri et POST /documents/recherche : même ordre (date du document décroissante) [P-21]",
                "/recherches " + ordre(contrat, noms) + ", /documents/recherche " + ordre(meta, noms));
        verif("E7004-R2-03", liste.equals(attendu),
                "GET /documents (liste, tri par défaut sur la date du document) : même ordre que la recherche [P-21, 12.7]",
                "liste " + ordre(liste, noms));
    }

    // ============================================================== ANO-E7-005 : compléments
    static JsonNode job(String id) throws Exception {
        return g.get("/api/v1/archivage/jobs/" + id, tAdmin).json();
    }

    static String statutNoeud(String id) throws Exception {
        return g.get("/api/v1/archivage/dossiers/" + id, tAdmin).json().path("statutConservation").asText();
    }

    static void e7005() throws Exception {
        String X = espace("QA-R2-arch-" + m, "QAR2A-" + m, null);
        String C1 = espace("QA-R2-arch-enfant-" + m, "QAR2AE-" + m, X);
        String Y = espace("QA-R2-hors-" + m, "QAR2H-" + m, null);
        String Tx = type("QAR2AX-" + m, X);
        String Ty = type("QAR2AY-" + m, Y);
        String Tc = type("QAR2AC-" + m, C1);
        String dansX = id(deposer(tAdmin, Tx, "qar2-" + m + "-dans-x", null));
        String corbeilleX = id(deposer(tAdmin, Tx, "qar2-" + m + "-corbeille-x", null));
        String dansY = id(deposer(tAdmin, Ty, "qar2-" + m + "-dans-y", null));
        Rep supDoc = g.appel("DELETE", "/api/v1/documents/" + corbeilleX, tAdmin, null, null);
        Rep supC1 = g.appel("DELETE", "/api/v1/workspaces/" + C1, tAdmin, null, null);
        Rep arch = g.appel("POST", "/api/v1/archivage/dossiers/" + X, tAdmin, null, null);
        String J = arch.json().path("id").asText();
        JsonNode ja = job(J);
        for (int i = 0; i < 90 && !List.of("TERMINE", "ECHEC", "ANNULE").contains(ja.path("etat").asText()); i++) {
            Thread.sleep(1000);
            ja = job(J);
        }
        info("archivage de " + X + " : " + ja.path("etat").asText() + ", document dans X " + g.get("/api/v1/documents/" + dansX, tAdmin).json().path("statutConservation").asText()
                + ", corbeille document " + supDoc.code() + ", corbeille dossier enfant " + supC1.code());

        // Création par l'Administrateur (le scénario d'origine portait sur un membre habilité)
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", "QA-R2-sous-admin-" + m);
        e.put("code", "QAR2SA-" + m);
        e.put("employeId", eAdmin);
        e.put("parentId", X);
        Rep creation = g.json("POST", "/api/v1/workspaces", tAdmin, e);
        verif("E7005-R2-01", creation.code() == 409 && "DOSSIER_ARCHIVE".equals(creation.codeMetier()),
                "Administrateur : création d'un sous-dossier sous un dossier archivé refusée (409 DOSSIER_ARCHIVE) [D10, 12.6]",
                "HTTP " + creation.code() + " " + creation.codeMetier());

        // Déplacement d'un dossier actif sous le dossier archivé : PATCH parent, puis fiche (PUT)
        Rep patch = g.json("PATCH", "/api/v1/workspaces/" + Y + "/parent", tAdmin, Map.of("parentId", X));
        Map<String, Object> fiche = new LinkedHashMap<>();
        fiche.put("name", "QA-R2-hors-" + m);
        fiche.put("code", "QAR2H-" + m);
        fiche.put("employeId", eAdmin);
        fiche.put("parentId", X);
        Rep put = g.json("PUT", "/api/v1/workspaces/" + Y, tAdmin, fiche);
        verif("E7005-R2-02", patch.code() == 409 && put.code() == 409 && "DOSSIER_ARCHIVE".equals(patch.codeMetier()) && "DOSSIER_ARCHIVE".equals(put.codeMetier()),
                "Déplacement d'un dossier actif sous un dossier archivé refusé : PATCH …/parent et changement de parent par la fiche (409 DOSSIER_ARCHIVE) [D10]",
                "PATCH parent " + patch.code() + " " + patch.codeMetier() + ", PUT fiche " + put.code() + " " + put.codeMetier());

        // Document : déplacement et rattachement vers le dossier archivé
        Rep dep = g.json("PATCH", "/api/v1/documents/" + dansY + "/emplacement", tAdmin, Map.of("noeudId", X));
        Rep rat = g.json("POST", "/api/v1/documents/" + dansY + "/rattachements", tAdmin, Map.of("noeudId", X));
        verif("E7005-R2-03", dep.code() == 409 && rat.code() == 409,
                "Document actif déplacé ou rattaché dans un dossier archivé : refusé (409) [D10, 12.6]",
                "déplacement " + dep.code() + " " + dep.codeMetier() + ", rattachement " + rat.code() + " " + rat.codeMetier());

        // Restauration d'un dossier enfant mis à la corbeille AVANT l'archivage du parent
        Rep resC1 = g.appel("PATCH", "/api/v1/workspaces/" + C1 + "/restore", tAdmin, null, null);
        String stC1 = statutNoeud(C1);
        Rep depC1 = deposer(tAdmin, Tc, "qar2-" + m + "-dans-enfant-restaure", null);
        verif("E7005-R2-04", "ARCHIVE".equals(stC1) && depC1.code() == 409,
                "Dossier enfant mis à la corbeille avant l'archivage du parent, puis restauré : il revient ARCHIVE, le dépôt y est refusé (409) [D10]",
                "restauration " + resC1.code() + ", statut " + stC1 + ", dépôt " + depC1.code() + " " + depC1.codeMetier());

        // Restauration d'un document mis à la corbeille AVANT l'archivage du dossier
        Rep resDoc = g.appel("PATCH", "/api/v1/documents/" + corbeilleX + "/restore", tAdmin, null, null);
        JsonNode fdoc = g.get("/api/v1/documents/" + corbeilleX, tAdmin).json();
        Object[] mp = multipart(pdf2, "v2.pdf", "application/pdf", Map.of(), null);
        Rep vers = g.appel("POST", "/api/v1/documents/" + corbeilleX + "/versions", tAdmin, "multipart/form-data; boundary=" + mp[0], (byte[]) mp[1]);
        Map<String, Object> maj = new LinkedHashMap<>();
        maj.put("name", "qar2-" + m + "-corbeille-x-modifie");
        maj.put("typeDocumentId", Tx);
        Rep put2 = g.json("PUT", "/api/v1/documents/" + corbeilleX, tAdmin, maj);
        String st = fdoc.path("statutConservation").asText();
        boolean ecrit = vers.code() / 100 == 2 || put2.code() / 100 == 2;
        res("E7005-R2-05", !ecrit ? "OK" : "ECHEC",
                "Document mis à la corbeille avant l'archivage du dossier, puis restauré : aucune écriture possible dans le dossier archivé [D10, 12.6]",
                "restauration " + resDoc.code() + ", statut du document " + st + " (dossier " + statutNoeud(X) + "), versement " + vers.code() + " " + vers.codeMetier()
                        + ", modification de la fiche " + put2.code() + " " + put2.codeMetier());
    }

    // ============================================================== T-059 : vérification à la demande
    static Path fichierCoffre(String cleFichier) {
        Path racine = Path.of(env("GED_STOCKAGE_RACINE", "run/coffre"));
        return racine.resolve(cleFichier.substring(0, 2)).resolve(cleFichier.substring(2, 4)).resolve(cleFichier + ".enc");
    }

    static void t059() throws Exception {
        String tTiers = g.connecter(env("GED_E3_TIERS", "yalaoui"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String S = espace("QA-R2-integ-" + m, "QAR2I-" + m, null);
        String T = type("QAR2I-" + m, S);
        String doc = id(deposer(tAdmin, T, "qar2-" + m + "-integrite", null));
        Rep sain = g.appel("POST", "/api/v1/admin/integrite/documents/" + doc, tAdmin, null, null);
        Rep tiers = g.appel("POST", "/api/v1/admin/integrite/documents/" + doc, tTiers, null, null);
        Rep dep = g.appel("POST", "/api/v1/admin/integrite/documents/" + doc, tDep, null, null);
        Rep fondsTiers = g.appel("POST", "/api/v1/admin/integrite/verification", tTiers, null, null);
        Rep inconnu = g.appel("POST", "/api/v1/admin/integrite/documents/" + UUID.randomUUID(), tAdmin, null, null);
        verif("T059-R2-01", sain.code() == 200 && sain.json().path("conforme").asBoolean() && tiers.code() == 403 && dep.code() == 403
                        && fondsTiers.code() == 403 && inconnu.code() == 404,
                "Vérification d'intégrité d'un document à la demande : conforme ; réservée à l'Administrateur (tiers et déposant 403) ; document inconnu 404 [6.1.4, T-059]",
                "Administrateur " + sain.code() + " " + sain.corps().replaceAll("\\s+", " ").substring(0, Math.min(160, sain.corps().length()))
                        + " ; tiers " + tiers.code() + ", déposant " + dep.code() + ", fonds par le tiers " + fondsTiers.code() + ", inconnu " + inconnu.code());
        if (db == null) {
            res("T059-R2-02", "NA", "Altération détectée à la demande", "GED_R2_JDBC absent");
        } else {
            String cle = sql1("SELECT cle_fichier_id::text FROM version_document WHERE document_id = ?::uuid ORDER BY numero DESC LIMIT 1", doc);
            Path f = fichierCoffre(cle);
            Path copie = Files.createTempFile("qa-r2-integ", ".enc");
            Files.copy(f, copie, StandardCopyOption.REPLACE_EXISTING);
            byte[] o = Files.readAllBytes(f);
            o[Math.min(200, o.length - 1)] ^= (byte) 0xFF;
            Files.write(f, o);
            Rep altere = g.appel("POST", "/api/v1/admin/integrite/documents/" + doc, tAdmin, null, null);
            Rep tel = g.get("/api/v1/documents/" + doc + "/download", tAdmin);
            Files.copy(copie, f, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(copie);
            Rep apres = g.appel("POST", "/api/v1/admin/integrite/documents/" + doc, tAdmin, null, null);
            Thread.sleep(1500);
            String audit = sql1("SELECT string_agg(action || ':' || resultat || ':' || coalesce(objet_type, ''), ', ' ORDER BY horodatage) FROM journal_audit"
                    + " WHERE action LIKE 'INTEGRITE%' AND (objet_id = ?::uuid OR objet_id = ?::uuid)", doc, cle);
            String motif = sql1("SELECT motif FROM journal_audit WHERE objet_id = ?::uuid AND action = 'INTEGRITE_VERIFIEE' AND resultat = 'ECHEC' LIMIT 1", doc);
            verif("T059-R2-02", altere.code() == 200 && !altere.json().path("conforme").asBoolean() && altere.corps().contains("ALTERE")
                            && apres.json().path("conforme").asBoolean() && audit != null && audit.contains("INTEGRITE_ANOMALIE") && audit.contains("INTEGRITE_VERIFIEE:ECHEC"),
                    "Fichier chiffré altéré dans le coffre : la vérification à la demande le signale ALTERE, auditée (INTEGRITE_VERIFIEE en échec + INTEGRITE_ANOMALIE) ; restauré : conforme [6.1.4, T-059]",
                    "altéré : " + altere.code() + " " + altere.corps().replaceAll("\\s+", " ").substring(0, Math.min(200, altere.corps().length()))
                            + " ; téléchargement pendant l'altération " + tel.code() + " " + tel.codeMetier() + " ; restauré : conforme=" + apres.json().path("conforme")
                            + " ; audit " + audit + " ; motif « " + motif + " »");
        }
        Rep f1 = g.appel("POST", "/api/v1/admin/integrite/verification", tAdmin, null, null);
        Rep f2 = g.appel("POST", "/api/v1/admin/integrite/verification", tAdmin, null, null);
        JsonNode etat = g.get("/api/v1/admin/integrite/verification", tAdmin).json();
        String e0 = etat.toString();
        for (int i = 0; i < 120 && etat.path("enCours").asBoolean(false); i++) {
            Thread.sleep(1000);
            etat = g.get("/api/v1/admin/integrite/verification", tAdmin).json();
        }
        String auditFonds = db == null ? "?" : sql1("SELECT count(*) FROM journal_audit WHERE action = 'INTEGRITE_VERIFIEE' AND motif LIKE '%fonds%' AND horodatage > now() - interval '5 minutes'");
        verif("T059-R2-03", f1.code() == 202 && f2.code() == 409 && !etat.path("enCours").asBoolean(true) && !"0".equals(auditFonds),
                "Vérification du fonds entier à la demande : lancée en tâche de fond (202), une seconde demande pendant la passe est refusée (409), état consultable, lancement audité [6.1.4, T-059]",
                "lancement " + f1.code() + ", seconde demande " + f2.code() + " " + f2.codeMetier() + ", état pendant " + e0 + ", état final " + etat + ", audit FONDS " + auditFonds);
    }

    // ============================================================== T-055 / D15 : compte désactivé après coup
    static void d15() throws Exception {
        String cTiers = env("GED_R2_COMPTE_D15", "yalaoui");
        int port = Integer.parseInt(env("GED_V8_LDAP_PORT", "33394"));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("code", "qa-r2-d15-" + m.toLowerCase());
        a.put("nom", "Recette qa tour 2 D15");
        a.put("description", "Délégation, compte désactivé après coup (D15)");
        a.put("adressesAutorisees", List.of("127.0.0.1", "::1"));
        a.put("quotaMinute", 600);
        a.put("quotaJour", 100000);
        String app = id(g.json("POST", "/api/v1/applications", tAdmin, a));
        Rep k = g.json("POST", "/api/v1/applications/" + app + "/cles", tAdmin, Map.of("delegation", true));
        String idCle = k.json().path("details").path("id").asText(), secret = k.json().path("cle").asText();
        String S = espace("QA-R2-d15-" + m, "QAR2D-" + m, null);
        g.json("PUT", "/api/v1/cles-api/" + idCle + "/portee", tAdmin, Map.of("portee", List.of(Map.of("noeudId", S, "operations", List.of("CONSULTATION", "RECHERCHE")))));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-API-Key", secret);
        h.put("X-On-Behalf-Of", cTiers);
        Rep avant = g.appel("GET", "/api/v1/documents?size=1", h, null, null);
        try (LDAPConnection c = new LDAPConnection("localhost", port, "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma", mdp)) {
            SearchResultEntry fiche = c.search("DC=marchicamed,DC=ma", SearchScope.SUB, "(sAMAccountName=" + cTiers + ")", "userAccountControl").getSearchEntries().get(0);
            String uac0 = fiche.getAttributeValue("userAccountControl");
            long t0 = System.currentTimeMillis();
            c.modify(fiche.getDN(), new Modification(ModificationType.REPLACE, "userAccountControl", "514"));
            Rep aussitot = g.appel("GET", "/api/v1/documents?size=1", h, null, null);
            long refusA = -1;
            Rep r = aussitot;
            while (System.currentTimeMillis() - t0 < 200_000) {
                if (r.code() == 422) { refusA = System.currentTimeMillis() - t0; break; }
                Thread.sleep(5000);
                r = g.appel("GET", "/api/v1/documents?size=1", h, null, null);
            }
            String codeRefus = r.code() + " " + r.codeMetier();
            // Remise en état : compte réactivé ; délai jusqu'à la nouvelle acceptation
            if (uac0 == null) c.modify(fiche.getDN(), new Modification(ModificationType.DELETE, "userAccountControl"));
            else c.modify(fiche.getDN(), new Modification(ModificationType.REPLACE, "userAccountControl", uac0));
            long t1 = System.currentTimeMillis(), retourA = -1;
            Rep r2 = g.appel("GET", "/api/v1/documents?size=1", h, null, null);
            while (System.currentTimeMillis() - t1 < 200_000) {
                if (r2.code() == 200) { retourA = System.currentTimeMillis() - t1; break; }
                Thread.sleep(5000);
                r2 = g.appel("GET", "/api/v1/documents?size=1", h, null, null);
            }
            String motif = db == null ? "?" : sql1("SELECT motif FROM journal_audit WHERE action = 'CLE_API_REFUSEE' AND motif LIKE ? ORDER BY horodatage DESC LIMIT 1", "%(" + cTiers + ")%");
            verif("D15-R2-01", avant.code() == 200 && refusA >= 0 && refusA <= 135_000 && r.code() == 422 && "IDENTITE_DELEGUEE_INVALIDE".equals(r.codeMetier())
                            && motif != null && motif.contains("désactivé"),
                    "Compte désactivé APRÈS coup dans l'annuaire : la délégation est refusée (422 IDENTITE_DELEGUEE_INVALIDE) au plus tard à l'expiration du cache (2 min), motif au journal d'audit [5.5, D15, R28]",
                    "avant " + avant.code() + " ; userAccountControl " + uac0 + " → 514 ; aussitôt " + aussitot.code() + " ; refus après " + (refusA < 0 ? "jamais (200 s)" : (refusA / 1000) + " s")
                            + " (" + codeRefus + ") ; motif « " + motif + " »");
            verif("D15-R2-02", retourA >= 0 && retourA <= 135_000,
                    "Compte réactivé : délégation de nouveau acceptée au plus tard à l'expiration du cache [D15]",
                    "userAccountControl remis à " + uac0 + " ; acceptée après " + (retourA < 0 ? "jamais (200 s)" : (retourA / 1000) + " s") + " (" + r2.code() + ")");
        }
    }
}
