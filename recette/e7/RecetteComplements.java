import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compléments de recette E7 (vague 7) : T-105 (confidentialité par défaut du type), P-21 (date du
 * document, clé de tri prioritaire), R-03 / D12 (espace d'échange : déposer, télécharger, modifier
 * localement, verser, sous habilitation de GROUPE ; aucune édition en ligne ; aucune écriture dans
 * un dossier archivé), T-101 (annulation d'un archivage de dossier, et reprise après interruption).
 *
 * <p>Modes : sans argument, tous les contrôles sauf la reprise ; {@code reprise-preparer} lance un
 * archivage de dossier et rend la main dès la première tranche traitée (écrit JOB=… dans
 * GED_E7_ETAT) ; {@code reprise-verifier} contrôle, après redémarrage de l'instance, que le même
 * travail a repris et s'est terminé sans doublon. L'instance doit tourner avec des tranches
 * courtes (--ged.cycledevie.archivage.tranche=2) et un bail court (--ged.cycledevie.travailleur.bail=PT20S).
 */
public class RecetteComplements extends ClientGed {

    RecetteComplements(String url) {
        super(url);
    }

    static RecetteComplements g;
    static String tAdmin;
    static byte[] pdf;

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static Map<String, Object> type(String code, String espace, String confidentialite, Integer duree) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("typeDeDocument", code);
        t.put("description", "recette vague 7");
        t.put("workspaceId", espace);
        t.put("typeAutorise", List.of("pdf"));
        t.put("tailleMaxMo", 5);
        if (confidentialite != null) t.put("confidentialiteDefaut", confidentialite);
        if (duree != null) {
            t.put("dureeConservationMois", duree);
            t.put("pointDepart", "DATE_DOCUMENT");
        }
        return t;
    }

    static String deposer(String jeton, String type, String nom, String dateDocument, byte[] contenu) throws Exception {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("name", nom);
        c.put("typeDocumentId", type);
        if (dateDocument != null) c.put("dateDocument", dateDocument);
        return id(g.deposer(jeton, contenu, nom + ".pdf", "application/pdf", c, null));
    }

    static String espace(String nom, String code, String employe, String parent, String usage) throws Exception {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", nom);
        e.put("code", code);
        e.put("employeId", employe);
        if (parent != null) e.put("parentId", parent);
        if (usage != null) e.put("usageEspace", usage);
        return id(g.json("POST", "/api/v1/workspaces", tAdmin, e));
    }

    static JsonNode job(String id) throws Exception {
        return g.get("/api/v1/archivage/jobs/" + id, tAdmin).json();
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteComplements(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        pdf = Files.readAllBytes(Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf"));
        byte[] pdf2 = Files.readAllBytes(Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_facture.pdf"));
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String eAdmin = g.get("/api/v1/auth/me", tAdmin).json().path("employeId").asText();
        String m = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        Path etat = Path.of(env("GED_E7_ETAT", "etat-reprise.txt"));
        String mode = args.length > 0 ? args[0] : "tout";

        if (mode.equals("reprise-preparer") || mode.equals("tout")) {
            // Dossier de GED_T101_DOCS documents (60) à archiver, en tranches de 2.
            String D = espace("QA-T101-reprise-" + m, "QAT101R-" + m, eAdmin, null, null);
            String T = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAT101R-" + m, D, null, null)));
            int nb = Integer.parseInt(env("GED_T101_DOCS", "60"));
            for (int i = 0; i < nb; i++) deposer(tAdmin, T, "qat101r-" + m + "-" + i, null, pdf);
            if (mode.equals("reprise-preparer")) {
                Rep j = g.appel("POST", "/api/v1/archivage/dossiers/" + D, tAdmin, null, null);
                String J = j.json().path("id").asText();
                JsonNode s = job(J);
                for (int i = 0; i < 120 && s.path("traites").asInt() < 2; i++) {
                    Thread.sleep(500);
                    s = job(J);
                }
                Files.writeString(etat, "JOB=" + J + "\nDOSSIER=" + D + "\nTRAITES=" + s.path("traites").asInt() + "\n", StandardCharsets.UTF_8);
                info("job " + J + " interrompable : " + s.path("etat").asText() + ", " + s.path("traites").asInt() + "/" + s.path("total").asInt());
                System.exit(0);
            }
        }
        if (mode.equals("reprise-verifier")) {
            Map<String, String> e = new LinkedHashMap<>();
            for (String l : Files.readAllLines(etat)) if (l.contains("=")) e.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1));
            String J = e.get("JOB");
            JsonNode s = job(J);
            for (int i = 0; i < 180 && !List.of("TERMINE", "ANNULE", "ECHEC").contains(s.path("etat").asText()); i++) {
                Thread.sleep(1000);
                s = job(J);
            }
            JsonNode elements = g.get("/api/v1/archivage/jobs/" + J + "/elements?taille=200", tAdmin).json();
            int nonTraites = 0;
            for (JsonNode x : elements.isArray() ? elements : elements.path("content")) if (x.path("resultat").isNull() || x.path("resultat").asText().isEmpty()) nonTraites++;
            String statutDossier = g.get("/api/v1/archivage/dossiers/" + e.get("DOSSIER"), tAdmin).json().path("statutConservation").asText();
            JsonNode ev = g.get("/api/v1/audit/evenements?action=DOCUMENT_ARCHIVE&taille=200", tAdmin).json().path("content");
            java.util.Map<String, Integer> parDoc = new java.util.HashMap<>();
            for (JsonNode x : ev) parDoc.merge(x.path("objetId").asText(), 1, Integer::sum);
            int doublons = 0;
            for (JsonNode x : elements.isArray() ? elements : elements.path("content")) if (parDoc.getOrDefault(x.path("documentId").asText(), 0) > 1) doublons++;
            String auKill = e.getOrDefault("AU_KILL", "");
            verif("T101-03", auKill.startsWith("EN_COURS|") && "TERMINE".equals(s.path("etat").asText()) && s.path("traites").asInt() == s.path("total").asInt()
                            && s.path("archives").asInt() == s.path("total").asInt() && nonTraites == 0 && doublons == 0 && "ARCHIVE".equals(statutDossier)
                            && Integer.parseInt(e.get("TRAITES")) < s.path("total").asInt(),
                    "Archivage de dossier interrompu (arrêt brutal de l'instance) puis REPRIS au redémarrage (bail expiré) : terminé sans doublon, dossier marqué [12.6, T-101, D10]",
                    "état au moment de l'arrêt " + auKill + ", interrompu à " + e.get("TRAITES") + "/" + s.path("total").asInt() + ", état final " + s.path("etat").asText() + " " + s.path("traites").asInt()
                            + " traités, " + s.path("archives").asInt() + " archivés, non traités " + nonTraites + ", documents archivés deux fois " + doublons + ", dossier " + statutDossier);
            System.exit(bilan("T-101 reprise " + g.url));
        }

        // ================================================================ T-105 confidentialité par défaut du type
        String S = espace("QA-V7-" + m, "QAV7-" + m, eAdmin, null, null);
        String Tp = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAV7P-" + m, S, "PRIVE", 60)));
        JsonNode tp = g.get("/api/v1/type-documents/" + Tp, tAdmin).json();
        String dp = deposer(tAdmin, Tp, "qav7-" + m + "-defaut", "2026-01-15", pdf);
        Map<String, String> c = new LinkedHashMap<>();
        c.put("name", "qav7-" + m + "-explicite");
        c.put("typeDocumentId", Tp);
        c.put("confidentialite", "PUBLIC");
        JsonNode dx = g.deposer(tAdmin, pdf, "x.pdf", "application/pdf", c, null).json();
        JsonNode fp = g.get("/api/v1/documents/" + dp, tAdmin).json();
        verif("T105-01", "PRIVE".equals(tp.path("confidentialiteDefaut").asText()) && "PRIVE".equals(fp.path("confidentialite").asText())
                        && "PUBLIC".equals(dx.path("confidentialite").asText()) && "2031-01-15".equals(fp.path("echeanceConservation").asText()),
                "Type : confidentialité par défaut appliquée au dépôt (surchargeable), durée de conservation portée par le type [12.7, T-105]",
                "type " + tp.path("confidentialiteDefaut") + ", dépôt sans choix " + fp.path("confidentialite") + ", avec PUBLIC " + dx.path("confidentialite")
                        + ", échéance " + fp.path("echeanceConservation").asText());

        // ================================================================ P-21 date du document, clé de tri prioritaire
        String Tt = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAV7T-" + m, S, "PUBLIC", null)));
        String a = deposer(tAdmin, Tt, "qav7-" + m + "-a", "2026-02-01", pdf);
        String b = deposer(tAdmin, Tt, "qav7-" + m + "-b", "2026-05-01", pdf);
        String cc = deposer(tAdmin, Tt, "qav7-" + m + "-c", "2026-03-01", pdf);
        List<String> attendu = List.of(b, cc, a);
        List<String> meta = new ArrayList<>(), liste = new ArrayList<>(), contrat = new ArrayList<>();
        for (JsonNode x : g.json("POST", "/api/v1/documents/recherche", tAdmin, Map.of("typeDocumentId", Tt)).json().path("content")) meta.add(x.path("id").asText());
        for (JsonNode x : g.get("/api/v1/documents?workspaceId=" + S + "&size=50", tAdmin).json().path("content"))
            if (List.of(a, b, cc).contains(x.path("id").asText())) liste.add(x.path("id").asText());
        Rep rc = g.json("POST", "/api/v1/recherches", tAdmin, Map.of("typeDocumentId", Tt));
        for (JsonNode x : rc.json().path("resultats")) contrat.add(x.path("documentId").asText());
        Rep triDate = g.get("/api/v1/documents?workspaceId=" + S + "&size=50&sortBy=dateDocument&sortDir=desc", tAdmin);
        List<String> listeTriee = new ArrayList<>();
        for (JsonNode x : triDate.json().path("content")) if (List.of(a, b, cc).contains(x.path("id").asText())) listeTriee.add(x.path("id").asText());
        verif("P21-01", meta.equals(attendu), "Recherche par métadonnées : date du document, clé de tri prioritaire (décroissante) [12.7, P-21]",
                meta.equals(attendu) ? "ordre b (05-01), c (03-01), a (02-01)" : "ordre obtenu " + noms(meta, a, b, cc));
        verif("P21-02", liste.equals(attendu) && contrat.equals(attendu),
                "Liste des documents et recherche du contrat (POST /recherches) : tri par défaut sur la date du document [P-21 « tri par défaut sur la date du document », 12.7]",
                "liste " + noms(liste, a, b, cc) + ", POST /recherches " + noms(contrat, a, b, cc) + ", sortBy=dateDocument " + noms(listeTriee, a, b, cc));

        // ================================================================ R-03 espace d'échange sous habilitation de groupe
        String cTiers = env("GED_E3_TIERS", "yalaoui");
        String tTiers = g.connecter(cTiers, mdp);
        JsonNode meTiers = g.get("/api/v1/auth/me", tTiers).json();
        String X = espace("QA-Marches-" + m, "QAMCH-" + m, eAdmin, null, "ECHANGE");
        String Xa = espace("CPS-lot-archive-" + m, "QAMCHA-" + m, eAdmin, X, null);
        Map<String, Object> grp = new LinkedHashMap<>();
        grp.put("code", "QA-GRP-" + m);
        grp.put("name", "Groupe marchés " + m);
        grp.put("workspaceIds", List.of());
        grp.put("userIds", List.of(meTiers.path("employeId").asText()));
        String G = id(g.json("POST", "/api/v1/access-groups", tAdmin, grp));
        String roleStd = null;
        for (JsonNode r : g.get("/api/v1/admin/roles", tAdmin).json()) if (r.path("code").asText().equals("UTILISATEUR_STANDARD")) roleStd = r.path("id").asText();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("sujetType", "GROUPE");
        h.put("sujetId", G);
        h.put("roleId", roleStd);
        h.put("noeudId", X);
        h.put("ruptureHeritage", false);
        Rep hab = g.json("POST", "/api/v1/admin/habilitations", tAdmin, h);
        String Tx = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAMCHT-" + m, X, "PUBLIC", null)));
        String Txa = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAMCHTA-" + m, Xa, "PUBLIC", null)));
        Rep dep = g.deposer(tTiers, pdf, "cps.pdf", "application/pdf", Map.of("name", "qamch-" + m + "-CPS", "typeDocumentId", Tx), null);
        String dX = dep.json().path("id").asText();
        Rep tel = g.get("/api/v1/documents/" + dX + "/download", tTiers);
        Object[] mp = multipart(pdf2, "cps-v2.pdf", "application/pdf", Map.of(), null);
        Rep vers = g.appel("POST", "/api/v1/documents/" + dX + "/versions", tTiers, "multipart/form-data; boundary=" + mp[0], (byte[]) mp[1]);
        Rep tel2 = g.get("/api/v1/documents/" + dX + "/download", tTiers);
        verif("R03-01", hab.code() == 201 && dep.code() / 100 == 2 && tel.code() == 200 && sha256(tel.octets()).equals(sha256(pdf))
                        && vers.code() / 100 == 2 && tel2.code() == 200 && sha256(tel2.octets()).equals(sha256(pdf2)),
                "Espace d'échange sous habilitation de GROUPE : déposer, télécharger, modifier localement, verser la nouvelle version, qui devient courante [R-03, D12]",
                "habilitation de groupe " + hab.code() + ", dépôt " + dep.code() + ", téléchargement " + tel.code() + ", versement " + vers.code() + ", version courante = fichier modifié "
                        + (tel2.code() == 200 && sha256(tel2.octets()).equals(sha256(pdf2))));
        JsonNode api = g.get("/v3/api-docs", tAdmin).json().path("paths");
        List<String> edition = new ArrayList<>();
        api.fieldNames().forEachRemaining(p -> {
            String q = p.toLowerCase();
            if (q.contains("edit") || q.contains("wopi") || q.contains("onlyoffice") || q.contains("collabora") || q.contains("coedition") || q.contains("verrou-edition"))
                edition.add(p);
        });
        Rep putContenu = g.appel("PUT", "/api/v1/documents/" + dX + "/contenu", Map.of("Authorization", "Bearer " + tTiers), "application/pdf", pdf2);
        verif("R03-02", edition.isEmpty() && (putContenu.code() == 405 || putContenu.code() == 404),
                "Aucune édition ni co-édition en ligne : aucune route d'édition, contenu non modifiable en place [R-03, D12]",
                "routes d'édition " + edition + ", PUT …/contenu " + putContenu.code());
        String dA = deposer(tTiers, Txa, "qamch-" + m + "-archive", null, pdf);
        Rep arch = g.appel("POST", "/api/v1/archivage/dossiers/" + Xa, tAdmin, null, null);
        String jobArch = arch.json().path("id").asText();
        JsonNode ja = job(jobArch);
        for (int i = 0; i < 60 && !"TERMINE".equals(ja.path("etat").asText()); i++) {
            Thread.sleep(1000);
            ja = job(jobArch);
        }
        Rep depArch = g.deposer(tTiers, pdf, "y.pdf", "application/pdf", Map.of("name", "qamch-" + m + "-apres", "typeDocumentId", Txa), null);
        Object[] mp2 = multipart(pdf2, "v.pdf", "application/pdf", Map.of(), null);
        Rep versArch = g.appel("POST", "/api/v1/documents/" + dA + "/versions", tTiers, "multipart/form-data; boundary=" + mp2[0], (byte[]) mp2[1]);
        Rep dossArch = g.json("POST", "/api/v1/workspaces", tTiers, Map.of("name", "Nouveau " + m, "code", "QAMCHN-" + m,
                "employeId", meTiers.path("employeId").asText(), "parentId", Xa));
        verif("R03-03", "TERMINE".equals(ja.path("etat").asText()) && depArch.code() == 409 && versArch.code() == 409 && dossArch.code() / 100 != 2,
                "Aucune écriture dans un dossier archivé de l'espace d'échange : dépôt, versement et création de dossier refusés [R-03, 12.6]",
                "archivage " + ja.path("etat").asText() + ", dépôt " + depArch.code() + " " + depArch.codeMetier() + ", versement " + versArch.code() + " " + versArch.codeMetier()
                        + ", dossier " + dossArch.code() + " " + dossArch.codeMetier());

        // ================================================================ T-101 annulation d'un archivage de dossier
        String Dn = espace("QA-T101-annul-" + m, "QAT101A-" + m, eAdmin, null, null);
        String Tn = id(g.json("POST", "/api/v1/type-documents", tAdmin, type("QAT101A-" + m, Dn, null, null)));
        for (int i = 0; i < 12; i++) deposer(tAdmin, Tn, "qat101a-" + m + "-" + i, null, pdf);
        // (1) annulation immédiate, avant toute tranche
        Rep j1 = g.appel("POST", "/api/v1/archivage/dossiers/" + Dn, tAdmin, null, null);
        Rep a1 = g.appel("POST", "/api/v1/archivage/jobs/" + j1.json().path("id").asText() + "/annulation", tAdmin, null, null);
        JsonNode s1 = job(j1.json().path("id").asText());
        for (int i = 0; i < 20 && !"ANNULE".equals(s1.path("etat").asText()); i++) {
            Thread.sleep(500);
            s1 = job(j1.json().path("id").asText());
        }
        verif("T101-01", j1.code() == 202 && a1.code() == 200 && "ANNULE".equals(s1.path("etat").asText()) && s1.path("archives").asInt() <= 2,
                "Archivage de dossier annulé avant de commencer (ou à la première tranche) : ANNULE [12.6, T-101]",
                "lancement " + j1.code() + ", annulation " + a1.code() + ", état " + s1.path("etat").asText() + ", archivés " + s1.path("archives").asInt());
        // (2) annulation en cours de traitement : prise en compte entre deux tranches
        Rep j2 = g.appel("POST", "/api/v1/archivage/dossiers/" + Dn, tAdmin, null, null);
        String J2 = j2.json().path("id").asText();
        JsonNode s2 = job(J2);
        for (int i = 0; i < 120 && s2.path("traites").asInt() < 2; i++) {
            Thread.sleep(250);
            s2 = job(J2);
        }
        int auMoment = s2.path("traites").asInt();
        Rep a2 = g.appel("POST", "/api/v1/archivage/jobs/" + J2 + "/annulation", tAdmin, null, null);
        for (int i = 0; i < 60 && !List.of("ANNULE", "TERMINE").contains(s2.path("etat").asText()); i++) {
            Thread.sleep(500);
            s2 = job(J2);
        }
        String statutDn = g.get("/api/v1/archivage/dossiers/" + Dn, tAdmin).json().path("statutConservation").asText();
        int restants = 0;
        for (JsonNode x : g.get("/api/v1/documents?workspaceId=" + Dn + "&size=50", tAdmin).json().path("content"))
            if ("ACTIF".equals(x.path("statutConservation").asText())) restants++;
        Rep a3 = g.appel("POST", "/api/v1/archivage/jobs/" + J2 + "/annulation", tAdmin, null, null);
        Rep j3 = g.appel("POST", "/api/v1/archivage/dossiers/" + Dn, tAdmin, null, null);
        verif("T101-02", a2.code() == 200 && "ANNULE".equals(s2.path("etat").asText()) && s2.path("traites").asInt() < 12 && restants > 0
                        && !"ARCHIVE".equals(statutDn) && a3.code() == 409 && j3.code() == 202,
                "Annulation en cours : prise en compte entre deux tranches, documents déjà archivés le restent, les autres restent actifs, dossier non marqué ; job clos 409 ; relance possible [12.6, T-101]",
                "annulé à " + auMoment + " traités → état " + s2.path("etat").asText() + " (" + s2.path("traites").asInt() + " traités), actifs restants " + restants
                        + ", dossier " + statutDn + ", réannulation " + a3.code() + ", relance " + j3.code());
        System.exit(bilan("Compléments E7 vague 7 " + g.url));
    }

    static String noms(List<String> ids, String a, String b, String c) {
        List<String> r = new ArrayList<>();
        for (String i : ids) r.add(i.equals(a) ? "a" : i.equals(b) ? "b" : i.equals(c) ? "c" : "?");
        return r.toString();
    }
}
