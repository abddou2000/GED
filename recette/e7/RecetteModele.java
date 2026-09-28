import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E7, partie modèle (DAT V3 §12.5, §12.7, §12.8, §12.9 ; D9, D12/R-03, Q4) : métadonnées
 * validées contre le plan (booléen compris), socle commun (objet, date du document), échéance de
 * conservation déduite du type (durée, point de départ), type utilisé non supprimable, plan
 * versionné, re-typologisation par lot, versions numérotées (auteur, empreinte, une seule
 * courante, ancienne en lecture seule), verrou (auteur, date, motif, 409 partout), déplacement de
 * document et de dossier audité, renommage unique (409), espaces métier et d'échange.
 *
 * <p>Jeu propre à chaque exécution (espace, index, plans, types, marqueur). Comptes : GED_E3_ADMIN
 * (Administrateur global), GED_E3_TIERS (habilité par le script). Sonde en base facultative
 * (D9) : GED_E7_JDBC_APP, connexion en ged_app (transaction annulée).
 */
public class RecetteModele extends ClientGed {

    RecetteModele(String url) {
        super(url);
    }

    static RecetteModele g;
    static String tAdmin;
    static byte[] pdf, pdf2;

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static Rep deposer(String type, String nom, String meta, String dateDocument, String objet) throws Exception {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("name", nom);
        c.put("typeDocumentId", type);
        if (dateDocument != null) c.put("dateDocument", dateDocument);
        if (objet != null) c.put("objet", objet);
        return g.deposer(tAdmin, pdf, nom + ".pdf", "application/pdf", c, meta);
    }

    static String index(String code, String nature, boolean obligatoire, String valeurs, String defaut) throws Exception {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("code", code);
        i.put("nomIndex", code);
        i.put("fieldType", nature);
        i.put("valeurs", valeurs);
        i.put("valeurParDefaut", defaut);
        i.put("obligatoire", obligatoire);
        i.put("indexePourRecherche", true);
        i.put("indexDeGroupage", false);
        return id(g.json("POST", "/api/v1/indices", tAdmin, i));
    }

    static Map<String, Object> plan(String code, List<String> indices) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("nomDuPlan", code);
        p.put("manuel", true);
        p.put("indexIds", indices);
        return p;
    }

    static Map<String, Object> type(String code, String espace, String plan, Integer duree, String depart, String indexDepart) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("typeDeDocument", code);
        t.put("description", "recette E7 modèle");
        t.put("workspaceId", espace);
        t.put("planIndexationId", plan);
        t.put("typeAutorise", List.of("pdf"));
        t.put("tailleMaxMo", 5);
        t.put("dureeConservationMois", duree);
        t.put("pointDepart", depart);
        t.put("pointDepartIndexCode", indexDepart);
        return t;
    }

    static JsonNode doc(String id) throws Exception {
        return g.get("/api/v1/documents/" + id, tAdmin).json();
    }

    static JsonNode audit(String action, String objetId) throws Exception {
        return g.get("/api/v1/audit/evenements?action=" + action + "&objetId=" + objetId + "&taille=20", tAdmin).json().path("content");
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteModele(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        pdf = Files.readAllBytes(donnees.resolve("pdf_texte_fr_convention.pdf"));
        pdf2 = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tTiers = g.connecter(env("GED_E3_TIERS", "yalaoui"), mdp);
        JsonNode meAdmin = g.get("/api/v1/auth/me", tAdmin).json(), meTiers = g.get("/api/v1/auth/me", tTiers).json();
        String eAdmin = meAdmin.path("employeId").asText();
        String roleStd = null;
        for (JsonNode r : g.get("/api/v1/admin/roles", tAdmin).json()) if (r.path("code").asText().equals("UTILISATEUR_STANDARD")) roleStd = r.path("id").asText();
        String m = Long.toString(System.currentTimeMillis(), 36).toUpperCase();

        // ---------------------------------------------------------------- jeu
        String S = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "QA-E7M-" + m, "code", "QAE7M-" + m, "employeId", eAdmin)));
        String F1 = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "Classeur " + m, "code", "QAE7F1-" + m, "employeId", eAdmin, "parentId", S)));
        String F1a = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "Sous-classeur " + m, "code", "QAE7F1A-" + m, "employeId", eAdmin, "parentId", F1)));
        String F2 = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "Archives courantes " + m, "code", "QAE7F2-" + m, "employeId", eAdmin, "parentId", S)));
        Map<String, Object> hab = new LinkedHashMap<>();
        hab.put("sujetType", "UTILISATEUR");
        hab.put("sujetId", meTiers.path("id").asText());
        hab.put("roleId", roleStd);
        hab.put("noeudId", S);
        hab.put("ruptureHeritage", false);
        id(g.json("POST", "/api/v1/admin/habilitations", tAdmin, hab));
        String cDate = "DATEF" + m, cMont = "MONT" + m, cStat = "STAT" + m, cUrg = "URG" + m, cNote = "NOTE" + m, cMont2 = "MNT2" + m;
        String iDate = index(cDate, "DATE", true, null, null), iMont = index(cMont, "NOMBRE", false, null, null),
                iStat = index(cStat, "LISTE", false, "Payée,Impayée", null), iUrg = index(cUrg, "BOOLEEN", false, null, null),
                iNote = index(cNote, "TEXTE", false, null, "sans observation"), iMont2 = index(cMont2, "NOMBRE", false, null, null);
        String P = id(g.json("POST", "/api/v1/plan-indexations", tAdmin, plan("QAPL-" + m, List.of(iDate, iMont, iStat, iUrg, iNote))));
        String P2 = id(g.json("POST", "/api/v1/plan-indexations", tAdmin, plan("QAPL2-" + m, List.of(iDate, iMont2))));
        String T = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAT-" + m, S, P, 24, "DATE_DOCUMENT", null)));
        String T2 = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAT2-" + m, F2, P2, null, "DATE_DOCUMENT", null)));

        // ---------------------------------------------------------------- M-01 métadonnées validées et normalisées
        String meta = "{\"" + cDate + "\":\"2026-06-15\",\"" + cMont + "\":\"1250,5\",\"" + cStat + "\":\"payée\",\"" + cUrg + "\":\"oui\"}";
        Rep d1r = deposer(T, "qae7m-" + m + "-d1", meta, "2026-01-31", "Facture fournisseur ACME");
        String d1 = id(d1r);
        JsonNode d1j = doc(d1), md = d1j.path("metadonnees");
        verif("M-01", md.path(cMont).isNumber() && md.path(cMont).asDouble() == 1250.5 && md.path(cUrg).isBoolean() && md.path(cUrg).asBoolean()
                        && "Payée".equals(md.path(cStat).asText()) && "2026-06-15".equals(md.path(cDate).asText())
                        && "Facture fournisseur ACME".equals(d1j.path("objet").asText()) && "2026-01-31".equals(d1j.path("dateDocument").asText()),
                "Métadonnées validées contre le plan et normalisées par nature (nombre, booléen, liste, date) ; objet et date du document portés [12.7, P-21]",
                "HTTP " + d1r.code() + " " + md + ", objet « " + d1j.path("objet").asText() + " », date " + d1j.path("dateDocument").asText());
        verif("M-02", "sans observation".equals(md.path(cNote).asText()), "Valeur par défaut d'un index appliquée quand elle est absente [12.7]", String.valueOf(md.path(cNote)));
        Rep inv = deposer(T, "qae7m-" + m + "-invalide", "{\"" + cMont + "\":\"beaucoup\",\"" + cStat + "\":\"Annulée\",\"" + cUrg + "\":\"peut-être\",\"INCONNU" + m + "\":1}", null, null);
        JsonNode err = inv.json().path("erreurs");
        Rep trouve = g.json("POST", "/api/v1/recherches", tAdmin, Map.of("texte", "qae7m-" + m + "-invalide"));
        verif("M-03", inv.code() == 400 && "METADONNEES_INVALIDES".equals(inv.codeMetier()) && err.has(cMont) && err.has(cStat) && err.has(cUrg)
                        && err.has("INCONNU" + m) && err.has(cDate),
                "Métadonnées invalides : 400 METADONNEES_INVALIDES, une erreur par champ (nombre, liste, booléen, inconnu, obligatoire manquant), rien déposé [12.7, 5.3.2]",
                "HTTP " + inv.code() + " " + inv.codeMetier() + " " + err);
        Rep rb = g.json("POST", "/api/v1/documents/recherche", tAdmin, Map.of("typeDocumentId", T, "criteres",
                List.of(Map.of("code", cUrg, "valeur", "oui"))));
        verif("M-04", rb.code() == 200 && rb.corps().contains(d1), "Recherche par métadonnée booléenne [12.7]", "HTTP " + rb.code() + " total " + rb.json().path("total"));

        // ---------------------------------------------------------------- M-05 échéance de conservation
        String e1 = d1j.path("echeanceConservation").asText();
        Rep tMaj = g.json("PUT", "/api/v1/type-documents/" + T, tAdmin, type("QAT-" + m, S, P, 36, "DATE_DOCUMENT", null));
        String e2 = doc(d1).path("echeanceConservation").asText();
        Rep tMet = g.json("PUT", "/api/v1/type-documents/" + T, tAdmin, type("QAT-" + m, S, P, 36, "METADONNEE", cNote));
        Rep tMet2 = g.json("PUT", "/api/v1/type-documents/" + T, tAdmin, type("QAT-" + m, S, P, 36, "METADONNEE", cDate));
        String e3 = doc(d1).path("echeanceConservation").asText();
        g.json("PUT", "/api/v1/type-documents/" + T, tAdmin, type("QAT-" + m, S, P, 24, "DATE_DOCUMENT", null));
        verif("M-05", "2028-01-31".equals(e1) && tMaj.code() == 200 && "2029-01-31".equals(e2) && tMet.code() == 400 && tMet2.code() == 200
                        && "2029-06-15".equals(e3),
                "Échéance déduite du type (durée, point de départ) et recalculée pour les documents du type ; point de départ sur un index non date refusé [12.9, T-105]",
                "24 mois depuis la date du document " + e1 + " ; 36 mois " + e2 + " ; index texte " + tMet.code() + " ; index date " + tMet2.code() + " → " + e3);

        // ---------------------------------------------------------------- M-06 plan versionné
        int vAvant = g.get("/api/v1/type-documents/" + T, tAdmin).json().path("versionPlan").asInt();
        String iRef = index("REF" + m, "TEXTE", true, null, null);
        Rep pMaj = g.json("PUT", "/api/v1/plan-indexations/" + P, tAdmin, plan("QAPL-" + m, List.of(iDate, iMont, iStat, iUrg, iNote, iRef)));
        int vApres = g.get("/api/v1/type-documents/" + T, tAdmin).json().path("versionPlan").asInt();
        Rep ancienModifie = g.json("PUT", "/api/v1/documents/" + d1, tAdmin, Map.of("metadonnees", Map.of(cDate, "2026-06-16")));
        Rep nouveauSansRef = deposer(T, "qae7m-" + m + "-sansref", "{\"" + cDate + "\":\"2026-02-01\"}", null, null);
        verif("M-06", pMaj.code() == 200 && vApres == vAvant + 1 && ancienModifie.code() == 200 && nouveauSansRef.code() == 400
                        && nouveauSansRef.json().path("erreurs").has("REF" + m),
                "Plan versionné : un index obligatoire ajouté crée une version ; l'ancien document se modifie contre SA version, un nouveau dépôt suit la nouvelle [12.7, T-105]",
                "version " + vAvant + " → " + vApres + ", ancien document " + ancienModifie.code() + ", nouveau dépôt sans REF " + nouveauSansRef.code());
        g.json("PUT", "/api/v1/plan-indexations/" + P, tAdmin, plan("QAPL-" + m, List.of(iDate, iMont, iStat, iUrg, iNote)));

        // ---------------------------------------------------------------- M-07 type utilisé non supprimable, désactivable
        Rep sup = g.appel("DELETE", "/api/v1/type-documents/" + T, tAdmin, null, null);
        Rep des = g.appel("PATCH", "/api/v1/type-documents/" + T + "/actif?actif=false", tAdmin, null, null);
        Rep depDes = deposer(T, "qae7m-" + m + "-desactive", "{\"" + cDate + "\":\"2026-02-01\"}", null, null);
        Rep act = g.appel("PATCH", "/api/v1/type-documents/" + T + "/actif?actif=true", tAdmin, null, null);
        verif("M-07", sup.code() == 409 && "TYPE_UTILISE".equals(sup.codeMetier()) && des.code() == 200 && depDes.code() == 400 && act.code() == 200,
                "Type utilisé : suppression refusée (409 TYPE_UTILISE, RESTRICT), désactivation possible, plus de dépôt sur un type désactivé [12.7, T-105]",
                "suppression " + sup.code() + " " + sup.codeMetier() + ", désactivation " + des.code() + ", dépôt " + depDes.code() + ", réactivation " + act.code());

        // ---------------------------------------------------------------- M-08 versions
        Object[] mp = multipart(pdf2, "v2.pdf", "application/pdf", Map.of(), null);
        Rep vers = g.appel("POST", "/api/v1/documents/" + d1 + "/versions", tAdmin, "multipart/form-data; boundary=" + mp[0], (byte[]) mp[1]);
        JsonNode vs = doc(d1).path("versions");
        JsonNode v1 = null, v2 = null;
        int courantes = 0;
        for (JsonNode v : vs) {
            if (v.path("numero").asInt() == 1) v1 = v;
            if (v.path("numero").asInt() == 2) v2 = v;
            if (v.path("principale").asBoolean()) courantes++;
        }
        Rep dlv1 = v1 == null ? null : g.get("/api/v1/documents/" + d1 + "/versions/" + v1.path("id").asText() + "/download", tAdmin);
        verif("M-08", vers.code() / 100 == 2 && v1 != null && v2 != null && courantes == 1 && v2.path("principale").asBoolean()
                        && sha256(pdf2).equals(v2.path("empreinte").asText()) && sha256(pdf).equals(v1.path("empreinte").asText())
                        && meAdmin.path("id").asText().equals(v2.path("auteurId").asText())
                        && dlv1.code() == 200 && sha256(dlv1.octets()).equals(sha256(pdf)),
                "Versions : numéro, empreinte SHA-256, auteur, une seule courante ; l'ancienne reste consultable à l'identique [12.8, T-106, D9]",
                "versement " + vers.code() + ", " + vs.size() + " versions, courantes " + courantes + ", ancienne téléchargée " + (dlv1 == null ? "—" : dlv1.code()));
        Rep defaut = g.appel("PATCH", "/api/v1/documents/" + d1 + "/versions/" + v1.path("id").asText() + "/default", tAdmin, null, null);
        int courantes2 = 0;
        for (JsonNode v : doc(d1).path("versions")) if (v.path("principale").asBoolean()) courantes2++;
        g.appel("PATCH", "/api/v1/documents/" + d1 + "/versions/" + v2.path("id").asText() + "/default", tAdmin, null, null);
        String sonde = "non exécutée (GED_E7_JDBC_APP absent)";
        boolean lectureSeule = true;
        String jdbc = env("GED_E7_JDBC_APP", null);
        if (jdbc != null) {
            try (Connection c = DriverManager.getConnection(jdbc)) {
                c.setAutoCommit(false);
                try {
                    c.createStatement().executeUpdate("UPDATE version_document SET file_name = 'falsifie.pdf' WHERE id = '" + v1.path("id").asText() + "'");
                    lectureSeule = false;
                    sonde = "UPDATE accepté";
                } catch (SQLException e) {
                    sonde = "UPDATE refusé : " + e.getMessage().lines().findFirst().orElse("");
                } finally {
                    c.rollback();
                }
            }
        }
        verif("M-09", defaut.code() == 200 && courantes2 == 1 && lectureSeule,
                "Ancienne version désignée courante (une seule courante, index unique) ; historique en lecture seule en base [12.8, D9]",
                "désignation " + defaut.code() + ", courantes " + courantes2 + " ; ged_app : " + sonde);

        // ---------------------------------------------------------------- M-10 verrou : auteur, date, motif, 409 partout
        String d3 = id(deposer(T, "qae7m-" + m + "-verrou", "{\"" + cDate + "\":\"2026-03-01\"}", null, null));
        Rep vT = g.appel("PATCH", "/api/v1/documents/" + d3 + "/verrou?verrouille=true&motif=Controle", tTiers, null, null);
        Rep vA = g.appel("PATCH", "/api/v1/documents/" + d3 + "/verrou?verrouille=true&motif=" + enc("Contrôle fiscal"), tAdmin, null, null);
        verif("M-10", vT.code() == 403 && vA.code() == 200 && vA.json().path("verrouille").asBoolean() && "Contrôle fiscal".equals(vA.json().path("verrouMotif").asText())
                        && !vA.json().path("verrouLe").isNull(),
                "Verrou posé par l'Administrateur seul, avec motif et date [12.8, T-107]",
                "standard " + vT.code() + ", Administrateur " + vA.code() + " motif « " + vA.json().path("verrouMotif").asText() + " », le " + vA.json().path("verrouLe").asText());
        Object[] mp3 = multipart(pdf2, "v.pdf", "application/pdf", Map.of(), null);
        Map<String, Rep> ecritures = new LinkedHashMap<>();
        ecritures.put("fiche", g.json("PUT", "/api/v1/documents/" + d3, tAdmin, Map.of("name", "qae7m-" + m + "-x")));
        ecritures.put("métadonnées", g.json("PUT", "/api/v1/documents/" + d3, tAdmin, Map.of("metadonnees", Map.of(cDate, "2026-03-02"))));
        ecritures.put("versement", g.appel("POST", "/api/v1/documents/" + d3 + "/versions", tAdmin, "multipart/form-data; boundary=" + mp3[0], (byte[]) mp3[1]));
        ecritures.put("déplacement", g.json("PATCH", "/api/v1/documents/" + d3 + "/emplacement", tAdmin, Map.of("noeudId", F2)));
        ecritures.put("rattachement", g.json("POST", "/api/v1/documents/" + d3 + "/rattachements", tAdmin, Map.of("noeudId", F2)));
        ecritures.put("réindexation", g.json("PUT", "/api/v1/indexation/documents/" + d3, tAdmin, Map.of("valeurs", List.of())));
        ecritures.put("archivage", g.appel("POST", "/api/v1/documents/" + d3 + "/archivage", tAdmin, null, null));
        ecritures.put("suppression", g.appel("DELETE", "/api/v1/documents/" + d3, tAdmin, null, null));
        List<String> ko = new ArrayList<>(), sansMotif = new ArrayList<>();
        ecritures.forEach((k, r) -> {
            if (r.code() != 409 || !"DOCUMENT_VERROUILLE".equals(r.codeMetier())) ko.add(k + " " + r.code() + " " + r.codeMetier());
            else if (!r.corps().contains("Contrôle fiscal")) sansMotif.add(k);
        });
        verif("M-11", ko.isEmpty(), "Document verrouillé : 409 DOCUMENT_VERROUILLE, sur fiche, métadonnées, versement, déplacement, rattachement, réindexation, archivage, suppression [12.8, Q4]",
                ko.isEmpty() ? ecritures.size() + " écritures refusées" + (sansMotif.isEmpty() ? ", motif cité partout" : ", motif non cité : " + sansMotif) : "non conformes : " + ko);
        Rep vL = g.appel("PATCH", "/api/v1/documents/" + d3 + "/verrou?verrouille=false", tAdmin, null, null);
        Rep apresL = g.json("PUT", "/api/v1/documents/" + d3, tAdmin, Map.of("name", "qae7m-" + m + "-libere"));
        boolean audite = !audit("DOCUMENT_VERROUILLE", d3).isEmpty() && audit("DOCUMENT_VERROUILLE", d3).toString().contains("Contrôle fiscal")
                && !audit("DOCUMENT_DEVERROUILLE", d3).isEmpty();
        verif("M-12", vL.code() == 200 && apresL.code() == 200 && audite,
                "Verrou levé : écritures de nouveau permises ; pose (motif compris) et levée auditées [12.8, 7.4.1]",
                "levée " + vL.code() + ", modification " + apresL.code() + ", audit " + audite);

        // ---------------------------------------------------------------- M-13 déplacement de document, audité
        Rep mvT = g.json("PATCH", "/api/v1/documents/" + d1 + "/emplacement", tTiers, Map.of("noeudId", F2));
        Rep mvA = g.json("PATCH", "/api/v1/documents/" + d1 + "/emplacement", tAdmin, Map.of("noeudId", F2));
        JsonNode evDep = audit("DOCUMENT_DEPLACE", d1).path(0);
        verif("M-13", mvT.code() == 403 && mvA.code() == 200 && F2.equals(mvA.json().path("workspace").path("id").asText())
                        && evDep.toString().contains(S) && evDep.toString().contains(F2),
                "Déplacement d'un document : Déplacer exigé (403 sinon) ; audité avec origine et destination [12.5, T-098]",
                "standard " + mvT.code() + ", Administrateur " + mvA.code() + ", audit " + (evDep.isMissingNode() ? "absent" : evDep.path("avant") + " → " + evDep.path("apres")));

        // ---------------------------------------------------------------- M-14 déplacement de dossier, anti-cycle, audité
        Rep mvF = g.json("PATCH", "/api/v1/workspaces/" + F1 + "/parent", tAdmin, Map.of("parentId", F2));
        String chemin = g.get("/api/v1/workspaces/" + F1a, tAdmin).corps();
        Rep cycle = g.json("PATCH", "/api/v1/workspaces/" + F2 + "/parent", tAdmin, Map.of("parentId", F1a));
        boolean arbre = false;
        for (JsonNode x : g.get("/api/v1/workspaces/tree", tAdmin).json()) {
            if (!x.path("id").asText().equals(S)) continue;
            for (JsonNode y : x.path("children")) if (y.path("id").asText().equals(F2))
                for (JsonNode z : y.path("children")) if (z.path("id").asText().equals(F1)) arbre = z.toString().contains(F1a);
        }
        JsonNode evF = audit("ESPACE_DEPLACE", F1).path(0);
        verif("M-15", mvF.code() == 200 && arbre && cycle.code() == 400 && evF.toString().contains(F2),
                "Déplacement d'un dossier avec sa sous-arborescence ; déplacement dans sa propre descendance refusé ; audité [12.5, T-098]",
                "déplacement " + mvF.code() + ", sous-dossier suivi " + arbre + ", cycle " + cycle.code() + ", audit " + (evF.isMissingNode() ? "absent" : evF.path("apres")));

        // ---------------------------------------------------------------- M-16 renommage unique (409)
        String dA = id(deposer(T, "qae7m-" + m + "-Rapport", "{\"" + cDate + "\":\"2026-03-01\"}", null, null));
        String dB = id(deposer(T, "qae7m-" + m + "-Brouillon", "{\"" + cDate + "\":\"2026-03-01\"}", null, null));
        Rep rn1 = g.json("PUT", "/api/v1/documents/" + dB, tAdmin, Map.of("name", "QAE7M-" + m.toLowerCase() + "-rapport"));
        Rep rn2 = g.json("PUT", "/api/v1/documents/" + dB, tAdmin, Map.of("name", "qae7m-" + m + "-Rapport final"));
        Rep rnF = g.json("PUT", "/api/v1/workspaces/" + F1, tAdmin, Map.of("name", "Classeur " + m, "code", "QAE7F1-" + m, "employeId", eAdmin, "parentId", F2));
        String F3 = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "Doublon " + m, "code", "QAE7F3-" + m, "employeId", eAdmin, "parentId", F2)));
        Rep rnF2 = g.json("PUT", "/api/v1/workspaces/" + F3, tAdmin, Map.of("name", "classeur " + m, "code", "QAE7F3-" + m, "employeId", eAdmin, "parentId", F2));
        boolean auditRen = audit("DOCUMENT_RENOMME", dB).toString().contains("Brouillon");
        verif("M-16", rn1.code() == 409 && "NOM_DEJA_UTILISE".equals(rn1.codeMetier()) && rn2.code() == 200 && auditRen
                        && rnF.code() == 200 && rnF2.code() == 409 && "NOM_DEJA_UTILISE".equals(rnF2.codeMetier()),
                "Renommage : nom unique dans le dossier (casse ignorée), 409 NOM_DEJA_UTILISE pour document et dossier ; renommage audité (avant / après) [12.5, P-20]",
                "document doublon " + rn1.code() + " " + rn1.codeMetier() + ", libre " + rn2.code() + ", audit " + auditRen + " ; dossier doublon " + rnF2.code());

        // ---------------------------------------------------------------- M-17 re-typologisation par lot
        String dR1 = id(deposer(T, "qae7m-" + m + "-retype1", "{\"" + cDate + "\":\"2026-04-01\",\"" + cMont + "\":42,\"" + cNote + "\":\"x\"}", null, null));
        String dR2 = id(deposer(T, "qae7m-" + m + "-retype2", "{\"" + cDate + "\":\"2026-04-02\"}", null, null));
        String dR3 = id(deposer(T, "qae7m-" + m + "-retype3", "{\"" + cDate + "\":\"2026-04-03\"}", null, null));
        g.appel("PATCH", "/api/v1/documents/" + dR3 + "/verrou?verrouille=true&motif=Litige", tAdmin, null, null);
        Map<String, Object> dem = new LinkedHashMap<>();
        dem.put("sourceTypeDocumentId", T);
        dem.put("cibleTypeDocumentId", T2);
        dem.put("correspondance", Map.of(cMont, cMont2));
        dem.put("documentIds", List.of(dR1, dR2, dR3));
        Rep jT = g.json("POST", "/api/v1/type-documents/retypages", tTiers, dem);
        Rep j = g.json("POST", "/api/v1/type-documents/retypages", tAdmin, dem);
        JsonNode job = j.json();
        for (int i = 0; i < 30 && !List.of("TERMINE", "ECHEC").contains(job.path("statut").asText()); i++) {
            Thread.sleep(1000);
            job = g.get("/api/v1/type-documents/retypages/" + j.json().path("id").asText(), tAdmin).json();
        }
        JsonNode r1 = doc(dR1);
        boolean verrouEchec = job.path("rapport").toString().contains("DOCUMENT_VERROUILLE");
        verif("M-17", jT.code() == 403 && j.code() == 202 && "TERMINE".equals(job.path("statut").asText()) && job.path("reussis").asInt() == 2
                        && job.path("echecs").asInt() == 1 && verrouEchec && T2.equals(r1.path("typeDocument").path("id").asText())
                        && r1.path("metadonnees").path(cMont2).asDouble() == 42 && F2.equals(r1.path("workspace").path("id").asText())
                        && !audit("DOCUMENT_RETYPE", dR1).isEmpty(),
                "Re-typologisation d'un lot (Administrateur) : travail de fond, correspondance des index appliquée, document verrouillé en échec au rapport, audit par document [12.7, D13]",
                "standard " + jT.code() + ", lancement " + j.code() + ", " + job.path("statut") + " réussis " + job.path("reussis") + " échecs " + job.path("echecs")
                        + ", métadonnée transposée " + r1.path("metadonnees").path(cMont2) + ", rapport " + job.path("rapport").toString().replaceAll("\\s+", " ").substring(0, Math.min(250, job.path("rapport").toString().length())));
        g.appel("PATCH", "/api/v1/documents/" + dR3 + "/verrou?verrouille=false", tAdmin, null, null);

        // ---------------------------------------------------------------- M-18 espaces métier et d'échange (R-03, D12)
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("name", "QA-Echange-" + m);
        ex.put("code", "QAECH-" + m);
        ex.put("employeId", eAdmin);
        ex.put("usageEspace", "ECHANGE");
        Rep exR = g.json("POST", "/api/v1/workspaces", tAdmin, ex);
        String X = id(exR);
        hab.put("noeudId", X);
        id(g.json("POST", "/api/v1/admin/habilitations", tAdmin, hab));
        String eTiers = meTiers.path("employeId").asText();
        Rep l1 = g.json("POST", "/api/v1/workspaces", tTiers, Map.of("name", "Lot 1 " + m, "code", "QALOT1-" + m, "employeId", eTiers, "parentId", X));
        Rep l2 = l1.code() == 201 ? g.json("POST", "/api/v1/workspaces", tTiers, Map.of("name", "Pièces " + m, "code", "QALOT2-" + m, "employeId", eTiers,
                "parentId", l1.json().path("id").asText())) : l1;
        Rep l3 = g.json("POST", "/api/v1/workspaces", tTiers, Map.of("name", "Intrus " + m, "code", "QALOT3-" + m, "employeId", eTiers, "parentId", S));
        verif("M-18", "ECHANGE".equals(exR.json().path("usageEspace").asText()) && l1.code() == 201 && "ECHANGE".equals(l1.json().path("usageEspace").asText())
                        && "DOSSIER".equals(l1.json().path("nature").asText()) && l2.code() == 201 && l3.code() == 403,
                "Espace d'échange : un membre habilité y crée dossiers et sous-dossiers (usage hérité) ; pas dans un espace métier (403) [R-03, D12]",
                "espace " + exR.json().path("usageEspace") + ", dossier " + l1.code() + " " + l1.json().path("usageEspace") + ", sous-dossier " + l2.code() + ", espace métier " + l3.code());
        System.exit(bilan("E7 modèle " + g.url));
    }
}
