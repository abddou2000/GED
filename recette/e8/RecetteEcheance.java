import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette T-112 — alerte d'échéance de conservation (DAT V3 §12.9, P4) : tâche quotidienne sous
 * verrou de tâche, signalement unique (marquage), notification « fin de conservation » aux seuls
 * Agents d'archive ayant Archiver sur le document, confidentialité respectée, audit
 * ECHEANCE_CONSERVATION_ATTEINTE, échéance repoussée puis de nouveau atteinte, aucune suppression,
 * filtre « échéance dépassée » (liste, recherche par métadonnées, plein texte).
 *
 * <p>À lancer contre une (ou deux) instance(s) dont la tâche tourne chaque minute
 * (GED_ALERTE_ECHEANCE_CRON="0 * * * * *") : la non-double exécution se prouve en faisant tourner
 * deux instances sur la même base et en vérifiant que chaque document n'est signalé qu'une fois.
 * Comptes : GED_E3_ADMIN (Administrateur), GED_E3_SANS_DROIT (standard), agents d'archive posés par
 * le script sur GED_E8_AGENT (qanouveau1, sur l'espace) et GED_E8_AGENT_AILLEURS (qanouveau3,
 * ailleurs). GED_E7_JDBC (lecture du marquage). Attente maximale par passage : GED_E8_ATTENTE_S (150).
 */
public class RecetteEcheance extends ClientGed {

    RecetteEcheance(String url) {
        super(url);
    }

    static RecetteEcheance g;
    static String tAdmin, jdbc;

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static JsonNode audit(String objetId) throws Exception {
        return g.get("/api/v1/audit/evenements?action=ECHEANCE_CONSERVATION_ATTEINTE&objetId=" + objetId + "&taille=20", tAdmin)
                .json().path("content");
    }

    static int notifs(String jeton, String doc) throws Exception {
        int n = 0;
        for (JsonNode x : g.get("/api/v1/notifications?taille=200", jeton).json().path("content"))
            if ("ECHEANCE_CONSERVATION".equals(x.path("type").asText()) && x.toString().contains(doc)) n++;
        return n;
    }

    static String signaleLe(String doc) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbc);
             ResultSet r = c.createStatement().executeQuery("SELECT echeance_signalee_le FROM document WHERE id = '" + doc + "'")) {
            return r.next() ? r.getString(1) : "absent";
        }
    }

    /** Attend que la condition (audit du document atteint n événements) soit vraie. */
    static boolean attendre(String doc, int n) throws Exception {
        int max = Integer.parseInt(env("GED_E8_ATTENTE_S", "150"));
        for (int i = 0; i < max / 5; i++) {
            if (audit(doc).size() >= n) return true;
            Thread.sleep(5000);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteEcheance(env("GED_URL", "http://localhost:18084"));
        jdbc = env("GED_E7_JDBC", null);
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        byte[] pdf = Files.readAllBytes(Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf"));
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tStd = g.connecter(env("GED_E3_SANS_DROIT", "nidrissi"), mdp);
        String tAgent = g.connecter(env("GED_E8_AGENT", "qanouveau1"), mdp);
        String tAilleurs = g.connecter(env("GED_E8_AGENT_AILLEURS", "qanouveau3"), mdp);
        String eAdmin = g.get("/api/v1/auth/me", tAdmin).json().path("employeId").asText();
        Map<String, String> roles = new LinkedHashMap<>();
        for (JsonNode r : g.get("/api/v1/admin/roles", tAdmin).json()) roles.put(r.path("code").asText(), r.path("id").asText());
        String m = Long.toString(System.currentTimeMillis(), 36);

        String Z = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "QA-T112-" + m, "code", "QAT112-" + m, "employeId", eAdmin)));
        String Y = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "QA-T112-ailleurs-" + m, "code", "QAT112Y-" + m, "employeId", eAdmin)));
        Map<String, Object> ty = new LinkedHashMap<>();
        ty.put("code", "QAT112T-" + m);
        ty.put("typeDeDocument", "Pièce conservée " + m);
        ty.put("description", "recette T-112");
        ty.put("workspaceId", Z);
        ty.put("typeAutorise", List.of("pdf"));
        ty.put("tailleMaxMo", 5);
        ty.put("dureeConservationMois", 1);
        ty.put("pointDepart", "DATE_DOCUMENT");
        String T = id(g.json("POST", "/api/v1/type-documents", tAdmin, ty));
        String[][] habs = {{g.get("/api/v1/auth/me", tAgent).json().path("id").asText(), "AGENT_ARCHIVE", Z},
                {g.get("/api/v1/auth/me", tAilleurs).json().path("id").asText(), "AGENT_ARCHIVE", Y},
                {g.get("/api/v1/auth/me", tStd).json().path("id").asText(), "UTILISATEUR_STANDARD", Z}};
        for (String[] h : habs) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sujetType", "UTILISATEUR");
            d.put("sujetId", h[0]);
            d.put("roleId", roles.get(h[1]));
            d.put("noeudId", h[2]);
            d.put("ruptureHeritage", false);
            id(g.json("POST", "/api/v1/admin/habilitations", tAdmin, d));
        }
        LocalDate passe = LocalDate.now().minusMonths(3), aujourdhui = LocalDate.now();
        Map<String, String> docs = new LinkedHashMap<>();
        String[][] defs = {{"pub", "PUBLIC", passe.toString()}, {"prive", "PRIVE", passe.toString()},
                {"conf", "CONFIDENTIEL", passe.toString()}, {"futur", "PUBLIC", aujourdhui.toString()}, {"repousse", "PUBLIC", passe.toString()}};
        for (String[] d : defs) {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("name", "qat112-" + m + "-" + d[0]);
            c.put("typeDocumentId", T);
            c.put("confidentialite", d[1]);
            c.put("dateDocument", d[2]);
            docs.put(d[0], id(g.deposer(tAdmin, pdf, d[0] + ".pdf", "application/pdf", c, null)));
        }

        // ---------------------------------------------------------------- E-01 filtre « échéance dépassée »
        JsonNode fiche = g.get("/api/v1/documents/" + docs.get("pub"), tAdmin).json();
        String liste = g.get("/api/v1/documents?workspaceId=" + Z + "&echeanceDepassee=true&size=50", tAdmin).corps();
        String rech = g.json("POST", "/api/v1/documents/recherche", tAdmin, Map.of("workspaceId", Z, "echeanceDepassee", true)).corps();
        String pt = g.get("/api/v1/recherche/plein-texte?q=" + enc("qat112-" + m) + "&echeanceDepassee=true&taille=50", tAdmin).corps();
        String ptTous = g.get("/api/v1/recherche/plein-texte?q=" + enc("qat112-" + m) + "&taille=50", tAdmin).corps();
        boolean listeOk = liste.contains(docs.get("pub")) && !liste.contains(docs.get("futur"));
        boolean rechOk = rech.contains(docs.get("pub")) && !rech.contains(docs.get("futur"));
        boolean ptOk = !pt.contains(docs.get("futur")) && (!ptTous.contains(docs.get("pub")) || pt.contains(docs.get("pub")));
        verif("E-01", fiche.path("echeanceDepassee").asBoolean() && listeOk && rechOk && ptOk,
                "Échéance dépassée mise en évidence (fiche) et filtrable : liste, recherche par métadonnées, plein texte [12.9, T-112]",
                "fiche " + fiche.path("echeanceDepassee") + " (échéance " + fiche.path("echeanceConservation").asText() + "), liste " + listeOk
                        + ", recherche " + rechOk + ", plein texte " + ptOk + (ptTous.contains(docs.get("pub")) ? "" : " (document pas encore indexé)"));

        // ---------------------------------------------------------------- E-02 signalement au passage de la tâche
        boolean signale = attendre(docs.get("pub"), 1) && attendre(docs.get("conf"), 1) && attendre(docs.get("prive"), 1) && attendre(docs.get("repousse"), 1);
        JsonNode ev = audit(docs.get("pub")).path(0);
        verif("E-02", signale && !"null".equals(String.valueOf(signaleLe(docs.get("pub")))) && audit(docs.get("futur")).isEmpty()
                        && signaleLe(docs.get("futur")) == null,
                "Documents échus signalés (marquage et audit ECHEANCE_CONSERVATION_ATTEINTE) ; document non échu ignoré [12.9, 7.4.1]",
                "signalé le " + signaleLe(docs.get("pub")) + ", audit " + ev.path("resultat").asText() + " " + ev.path("apres") + ", non échu " + signaleLe(docs.get("futur")));

        // ---------------------------------------------------------------- E-03 destinataires et confidentialité
        Thread.sleep(15000); // expédition asynchrone
        int aPub = notifs(tAgent, docs.get("pub")), aPriv = notifs(tAgent, docs.get("prive")), aConf = notifs(tAgent, docs.get("conf"));
        int ailleurs = notifs(tAilleurs, docs.get("pub")), std = notifs(tStd, docs.get("pub")), adm = notifs(tAdmin, docs.get("pub"));
        verif("E-03", aPub == 1 && aPriv == 1 && aConf == 0 && ailleurs == 0 && std == 0 && adm == 0,
                "Notification « fin de conservation » aux seuls Agents d'archive ayant Archiver sur le document ; confidentiel non notifié à qui ne peut le voir [12.9, 12.3]",
                "agent de l'espace : public " + aPub + ", privé " + aPriv + ", confidentiel " + aConf + " ; agent d'un autre espace " + ailleurs
                        + ", utilisateur standard " + std + ", Administrateur " + adm);

        // ---------------------------------------------------------------- E-04 signalement unique malgré les passages suivants (et deux instances)
        Thread.sleep(Integer.parseInt(env("GED_E8_ATTENTE_S", "150")) * 1000L);
        List<String> multiples = new ArrayList<>();
        for (String k : List.of("pub", "prive", "conf", "repousse")) if (audit(docs.get(k)).size() != 1) multiples.add(k + "=" + audit(docs.get(k)).size());
        int aPub2 = notifs(tAgent, docs.get("pub"));
        verif("E-04", multiples.isEmpty() && aPub2 == 1,
                "Signalement unique : passages suivants (et instances concurrentes) sans nouvel audit ni nouvelle notification [12.9, T-112]",
                multiples.isEmpty() ? "1 événement par document, notification " + aPub2 : "événements multiples " + multiples);

        // ---------------------------------------------------------------- E-05 échéance repoussée, puis de nouveau atteinte
        String rep = docs.get("repousse");
        Rep p1 = g.json("PUT", "/api/v1/documents/" + rep, tAdmin, Map.of("dateDocument", aujourdhui.toString()));
        String apresRepousse = signaleLe(rep);
        boolean depasseeApres = g.get("/api/v1/documents/" + rep, tAdmin).json().path("echeanceDepassee").asBoolean();
        Rep p2 = g.json("PUT", "/api/v1/documents/" + rep, tAdmin, Map.of("dateDocument", passe.minusDays(1).toString()));
        boolean resignale = attendre(rep, 2);
        verif("E-05", p1.code() == 200 && apresRepousse == null && !depasseeApres && p2.code() == 200 && resignale && audit(rep).size() == 2,
                "Échéance repoussée : signalement levé ; de nouveau atteinte : signalée une seconde fois [12.9, T-112]",
                "repoussée " + p1.code() + " (marquage " + apresRepousse + ", dépassée " + depasseeApres + "), de nouveau atteinte " + p2.code()
                        + " → audits " + audit(rep).size());

        // ---------------------------------------------------------------- E-06 aucune suppression automatique
        List<String> disparus = new ArrayList<>();
        for (var e : docs.entrySet()) {
            JsonNode d = g.get("/api/v1/documents/" + e.getValue(), tAdmin).json();
            if (!d.has("id") || d.path("supprime").asBoolean() || !"ACTIF".equals(d.path("statutConservation").asText())) disparus.add(e.getKey());
        }
        verif("E-06", disparus.isEmpty(), "Aucune suppression ni archivage automatique à l'échéance (P4, D10) [12.9]",
                disparus.isEmpty() ? "documents intacts" : "modifiés : " + disparus);
        System.exit(bilan("T-112 échéance " + g.url));
    }
}
