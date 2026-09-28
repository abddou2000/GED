import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E8 — workflow de validation parallèle (DAT V3 §12.8, revue client D7, D1/Q1, D13) et
 * E8-API (D8, R-01/R-02) : règle sur espace, dossier ou type (la plus spécifique), validateurs
 * nommés ou par rôle, circuit figé au dépôt, décisions sans ordre et motif au refus, statut sur la
 * version courante, caducité au versement, réaffectation tracée, annulation, diffusion,
 * notifications des circuits, audit ; pilotage et décision depuis une application tierce par clé
 * d'API avec délégation (portée WORKFLOW_PILOTAGE / WORKFLOW_DECISION), double identité auditée.
 *
 * <p>Le script construit son propre jeu (espace, dossier, types, règles) sous un marqueur par
 * exécution. Comptes (annuaire de recette) : Administrateur global (GED_E3_ADMIN), DG globale
 * (GED_E3_DEPOSANT), deux comptes habilités par le script (GED_E3_TIERS, GED_E3_SANS_DROIT) et un
 * compte sans aucun rôle (GED_E8_EXTERNE, défaut qanouveau1).
 */
public class RecetteWorkflow extends ClientGed {

    RecetteWorkflow(String url) {
        super(url);
    }

    static RecetteWorkflow g;
    static String tAdmin;
    static byte[] pdf, pdf2;
    static final String WF = "/api/v1/workflow";

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static String deposer(String jeton, String type, String nom) throws Exception {
        Rep r = g.deposer(jeton, pdf, nom + ".pdf", "application/pdf", Map.of("name", nom, "typeDocumentId", type), null);
        return id(r);
    }

    static JsonNode circuit(String doc, String jeton) throws Exception {
        return g.get(WF + "/documents/" + doc + "/circuits", jeton).json().path(0);
    }

    static Rep decider(String jeton, String circuit, String decision, String motif) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("decision", decision);
        if (motif != null) c.put("motif", motif);
        return g.json("POST", WF + "/circuits/" + circuit + "/decisions", jeton, c);
    }

    static String regle(String nom, List<Map<String, Object>> validateurs) throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> v : validateurs) {
            Map<String, Object> s = new LinkedHashMap<>(v);
            s.put("label", "V" + i);
            s.put("stepOrder", i++);
            steps.add(s);
        }
        return id(g.json("POST", WF + "/regles", tAdmin, Map.of("name", nom, "steps", steps)));
    }

    static Map<String, Object> nomme(String employeId) {
        return Map.of("employeId", employeId);
    }

    static String etat(JsonNode c, String employeId) {
        for (JsonNode v : c.path("validateurs")) if (employeId.equals(v.path("employeId").asText())) return v.path("etat").asText();
        return "absent";
    }

    static String validateurId(JsonNode c, String employeId) {
        for (JsonNode v : c.path("validateurs")) if (employeId.equals(v.path("employeId").asText())) return v.path("id").asText();
        return null;
    }

    /** Notifications de l'utilisateur : type → nombre, pour l'objet donné (document ou circuit). */
    static boolean notifie(String jeton, String type, String... objets) throws Exception {
        for (int i = 0; i < 10; i++) {
            String corps = g.get("/api/v1/notifications?taille=200", jeton).corps();
            JsonNode l = g.get("/api/v1/notifications?taille=200", jeton).json().path("content");
            for (JsonNode n : l) {
                if (!type.equals(n.path("type").asText())) continue;
                for (String o : objets) if (n.toString().contains(o)) return true;
            }
            if (corps.isEmpty()) return false;
            Thread.sleep(1000);
        }
        return false;
    }

    static JsonNode audit(String action, String objetId) throws Exception {
        return g.get("/api/v1/audit/evenements?action=" + action + (objetId == null ? "" : "&objetId=" + objetId) + "&taille=50", tAdmin)
                .json().path("content");
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteWorkflow(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        pdf = Files.readAllBytes(donnees.resolve("pdf_texte_fr_convention.pdf"));
        pdf2 = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
        String cAdmin = env("GED_E3_ADMIN", "sbennani"), cDg = env("GED_E3_DEPOSANT", "kelfassi"),
                cV2 = env("GED_E3_TIERS", "yalaoui"), cV3 = env("GED_E3_SANS_DROIT", "nidrissi"), cExt = env("GED_E8_EXTERNE", "qanouveau2");
        tAdmin = g.connecter(cAdmin, mdp);
        String tDg = g.connecter(cDg, mdp), tV2 = g.connecter(cV2, mdp), tV3 = g.connecter(cV3, mdp), tExt = g.connecter(cExt, mdp);
        JsonNode meAdmin = g.get("/api/v1/auth/me", tAdmin).json(), meDg = g.get("/api/v1/auth/me", tDg).json(),
                meV2 = g.get("/api/v1/auth/me", tV2).json(), meV3 = g.get("/api/v1/auth/me", tV3).json(), meExt = g.get("/api/v1/auth/me", tExt).json();
        String eAdmin = meAdmin.path("employeId").asText(), eDg = meDg.path("employeId").asText(),
                eV2 = meV2.path("employeId").asText(), eV3 = meV3.path("employeId").asText();
        String roleStd = null;
        for (JsonNode r : g.get("/api/v1/admin/roles", tAdmin).json()) if (r.path("code").asText().equals("UTILISATEUR_STANDARD")) roleStd = r.path("id").asText();
        String m = Long.toString(System.currentTimeMillis(), 36);

        // ---------------------------------------------------------------- jeu : espace E, dossier F, espace E2 sans règle
        String E = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "QA-E8-" + m, "code", "QAE8-" + m, "employeId", eAdmin)));
        String F = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "Dossier " + m, "code", "QAE8F-" + m, "employeId", eAdmin, "parentId", E)));
        String E2 = id(g.json("POST", "/api/v1/workspaces", tAdmin, Map.of("name", "QA-E8-libre-" + m, "code", "QAE8L-" + m, "employeId", eAdmin)));
        String[] t = new String[5];
        String[] lieux = {null, E, F, F, E2};
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> ty = new LinkedHashMap<>();
            ty.put("code", "QAE8T" + i + "-" + m);
            ty.put("typeDeDocument", "Pièce E8 " + i + " " + m);
            ty.put("description", "recette E8");
            ty.put("workspaceId", lieux[i]);
            ty.put("typeAutorise", List.of("pdf"));
            ty.put("tailleMaxMo", 5);
            t[i] = id(g.json("POST", "/api/v1/type-documents", tAdmin, ty));
        }
        for (String[] h : new String[][]{{meV2.path("id").asText(), E}, {meV3.path("id").asText(), E}}) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sujetType", "UTILISATEUR");
            d.put("sujetId", h[0]);
            d.put("roleId", roleStd);
            d.put("noeudId", h[1]);
            d.put("ruptureHeritage", false);
            id(g.json("POST", "/api/v1/admin/habilitations", tAdmin, d));
        }
        // Compte « externe » : un rôle ailleurs (Comptabilité), rien sur E (un compte sans aucun rôle reçoit 403 partout, P-04).
        String A = null;
        for (JsonNode x : g.get("/api/v1/workspaces/tree", tAdmin).json()) if (x.path("name").asText().equals("Comptabilité")) A = x.path("id").asText();
        Map<String, Object> hExt = new LinkedHashMap<>();
        hExt.put("sujetType", "UTILISATEUR");
        hExt.put("sujetId", meExt.path("id").asText());
        hExt.put("roleId", roleStd);
        hExt.put("noeudId", A);
        hExt.put("ruptureHeritage", false);
        g.json("POST", "/api/v1/admin/habilitations", tAdmin, hExt); // déjà posée aux exécutions suivantes
        String R1 = regle("QA-E8-R1-" + m, List.of(nomme(eDg), nomme(eV2)));
        String R2 = regle("QA-E8-R2-" + m, List.of(Map.of("roleId", roleStd, "perimetreNoeudId", E)));
        String R3 = regle("QA-E8-R3-" + m, List.of(nomme(eDg)));
        Rep p1 = g.json("PUT", WF + "/noeuds/" + E + "/regle", tAdmin, Map.of("regleId", R1));
        Rep p2 = g.json("PUT", WF + "/types/" + t[2] + "/regle", tAdmin, Map.of("regleId", R2));
        Rep p3 = g.json("PUT", WF + "/noeuds/" + F + "/regle", tAdmin, Map.of("regleId", R3));
        Rep p4 = g.json("PUT", WF + "/noeuds/" + E + "/regle", tV2, Map.of("regleId", R3));

        // ---------------------------------------------------------------- W-01 règle la plus spécifique
        String d1 = deposer(tAdmin, t[1], "qae8-" + m + "-d1");
        String dT = deposer(tAdmin, t[2], "qae8-" + m + "-type");
        String dF = deposer(tAdmin, t[3], "qae8-" + m + "-dossier");
        String dL = deposer(tAdmin, t[4], "qae8-" + m + "-libre");
        JsonNode r1 = g.get(WF + "/documents/" + d1 + "/regle", tAdmin).json(), rT = g.get(WF + "/documents/" + dT + "/regle", tAdmin).json(),
                rF = g.get(WF + "/documents/" + dF + "/regle", tAdmin).json();
        Rep rL = g.get(WF + "/documents/" + dL + "/regle", tAdmin);
        verif("W-01", p1.code() == 204 && p2.code() == 204 && p3.code() == 204
                        && R1.equals(r1.path("regleId").asText()) && "NOEUD".equals(r1.path("origine").asText())
                        && R2.equals(rT.path("regleId").asText()) && "TYPE".equals(rT.path("origine").asText())
                        && R3.equals(rF.path("regleId").asText()) && F.equals(rF.path("origineId").asText())
                        && rL.code() == 204 && circuit(dL, tAdmin).isMissingNode(),
                "Règle sur espace, dossier ou type : la plus spécifique s'applique (type, puis nœud le plus proche) ; sans règle, pas de circuit [12.8, 4.5.3]",
                "espace " + r1.path("origine") + ", type " + rT.path("origine") + ", dossier " + rF.path("origineId").asText().equals(F) + ", sans règle HTTP " + rL.code());
        verif("W-02", p4.code() == 403, "Rattacher une règle : réservé à la gestion des référentiels (403 pour un utilisateur standard) [12.8]", "HTTP " + p4.code());

        // ---------------------------------------------------------------- W-03 circuit ouvert au dépôt, validateurs parallèles
        JsonNode c1 = circuit(d1, tAdmin);
        String C1 = c1.path("id").asText();
        boolean inactif = !g.get("/api/v1/documents/" + d1, tAdmin).json().path("active").asBoolean(true);
        verif("W-03", "EN_COURS".equals(c1.path("statut").asText()) && c1.path("validateurs").size() == 2 && inactif
                        && "EN_ATTENTE".equals(etat(c1, eDg)) && "EN_ATTENTE".equals(etat(c1, eV2)),
                "Circuit ouvert au dépôt, deux validateurs nommés en attente en même temps, document non utilisable [12.8, D7]",
                c1.path("statut") + ", " + c1.path("validateurs").size() + " validateurs, document inactif " + inactif);

        // W-04 notifications d'ouverture : les deux validateurs, en même temps
        verif("W-04", notifie(tDg, "CIRCUIT_OUVERT", d1, C1) && notifie(tV2, "CIRCUIT_OUVERT", d1, C1)
                        && !notifie(tV3, "CIRCUIT_OUVERT", d1, C1),
                "Ouverture notifiée aux deux validateurs (et à eux seuls) [12.9, 4.5.3]", "");
        JsonNode aTraiter = g.get(WF + "/a-traiter", tV2).json();
        verif("W-05", aTraiter.toString().contains(C1), "« Mes validations » : le circuit figure dans la liste à traiter du validateur", "");

        // ---------------------------------------------------------------- W-06 décisions sans ordre
        Rep n1 = decider(tV3, C1, "VALIDE", null);
        Rep n2 = decider(tExt, C1, "VALIDE", null);
        verif("W-06", n1.code() == 403 && "PAS_VALIDATEUR".equals(n1.codeMetier()) && n2.code() == 404,
                "Décision d'un non-validateur : 403 PAS_VALIDATEUR s'il voit le document, 404 sinon [12.8, P5]",
                "habilité non validateur " + n1.code() + " " + n1.codeMetier() + ", hors périmètre " + n2.code());
        Rep a1 = decider(tV2, C1, "VALIDE", null);
        Rep a2 = decider(tDg, C1, "VALIDE", null);
        boolean actif = g.get("/api/v1/documents/" + d1, tAdmin).json().path("active").asBoolean();
        verif("W-07", a1.code() == 201 && "EN_COURS".equals(a1.json().path("statut").asText())
                        && a2.code() == 201 && "VALIDE".equals(a2.json().path("statut").asText())
                        && !a2.json().path("closLe").isNull() && actif,
                "Second validateur d'abord, puis premier : EN_COURS puis VALIDE quand tous ont validé ; document utilisable [12.8, D7]",
                a1.code() + " " + a1.json().path("statut") + " → " + a2.code() + " " + a2.json().path("statut") + ", actif " + actif);
        Rep a3 = decider(tDg, C1, "REFUSE", "trop tard");
        verif("W-08", a3.code() == 409 && "CIRCUIT_CLOS".equals(a3.codeMetier()), "Circuit validé : plus aucune décision (409 CIRCUIT_CLOS)", "HTTP " + a3.code() + " " + a3.codeMetier());
        verif("W-09", notifie(tAdmin, "CIRCUIT_DECISION", d1, C1), "Décision notifiée à l'initiateur [12.9]", "");

        // ---------------------------------------------------------------- W-10 refus : motif obligatoire, un refus suffit
        String d2 = deposer(tAdmin, t[1], "qae8-" + m + "-d2");
        String C2 = circuit(d2, tAdmin).path("id").asText();
        Rep f1 = decider(tDg, C2, "REFUSE", null);
        Rep f2 = decider(tDg, C2, "REFUSE", "Montant non conforme");
        verif("W-10", f1.code() == 400 && "MOTIF_OBLIGATOIRE".equals(f1.codeMetier()) && f2.code() == 201
                        && "REFUSE".equals(f2.json().path("statut").asText()),
                "Refus sans motif : 400 MOTIF_OBLIGATOIRE ; avec motif : un seul refus rend le circuit REFUSE [12.8, D7]",
                f1.code() + " " + f1.codeMetier() + " ; " + f2.code() + " " + f2.json().path("statut"));

        // ---------------------------------------------------------------- W-11 versement : décisions caduques, statut sur la version courante
        String d3 = deposer(tAdmin, t[1], "qae8-" + m + "-d3");
        String C3 = circuit(d3, tAdmin).path("id").asText();
        decider(tV2, C3, "VALIDE", null);
        Object[] mp = multipart(pdf2, "v2.pdf", "application/pdf", Map.of(), null);
        Rep vers = g.appel("POST", "/api/v1/documents/" + d3 + "/versions", tAdmin, "multipart/form-data; boundary=" + mp[0], (byte[]) mp[1]);
        JsonNode c3 = circuit(d3, tAdmin);
        boolean ancienneConservee = false;
        for (JsonNode x : c3.path("decisions")) ancienneConservee |= x.path("versionNumero").asInt() == 1 && "VALIDE".equals(x.path("decision").asText());
        verif("W-11", vers.code() / 100 == 2 && "EN_COURS".equals(c3.path("statut").asText()) && c3.path("versionCouranteNumero").asInt() == 2
                        && "EN_ATTENTE".equals(etat(c3, eV2)) && ancienneConservee,
                "Nouvelle version : décisions antérieures caduques (conservées), circuit EN_COURS sur la version 2 [12.8, 4.5.3]",
                "versement " + vers.code() + ", statut " + c3.path("statut") + ", version " + c3.path("versionCouranteNumero")
                        + ", état du validateur " + etat(c3, eV2) + ", décision v1 conservée " + ancienneConservee);
        decider(tV2, C3, "VALIDE", null);
        Rep v3b = decider(tDg, C3, "VALIDE", null);
        verif("W-12", "VALIDE".equals(v3b.json().path("statut").asText()), "Validation complète sur la version courante : VALIDE", v3b.json().path("statut").asText());

        // ---------------------------------------------------------------- W-13 validateur par rôle, résolu à la décision
        String CT = circuit(dT, tAdmin).path("id").asText();
        Rep rr1 = decider(tDg, CT, "VALIDE", null);
        Rep rr2 = decider(tV3, CT, "VALIDE", null);
        verif("W-13", rr1.code() == 403 && rr2.code() == 201 && "VALIDE".equals(rr2.json().path("statut").asText()),
                "Validateur par rôle sur un périmètre : le porteur du rôle décide, un autre (même DG) est refusé [12.8, 4.5.4]",
                "non porteur " + rr1.code() + " " + rr1.codeMetier() + ", porteur " + rr2.code() + " " + rr2.json().path("statut"));

        // ---------------------------------------------------------------- W-14 annulation, nouveau circuit
        String d4 = deposer(tAdmin, t[1], "qae8-" + m + "-d4");
        String C4 = circuit(d4, tAdmin).path("id").asText();
        decider(tV2, C4, "VALIDE", null);
        Rep x1 = g.json("POST", WF + "/circuits/" + C4 + "/annulation", tV2, Map.of("motif", "x"));
        Rep x2 = g.json("POST", WF + "/circuits/" + C4 + "/annulation", tAdmin, Map.of());
        Rep x3 = g.json("POST", WF + "/circuits/" + C4 + "/annulation", tAdmin, Map.of("motif", "Pièce remplacée"));
        verif("W-14", x1.code() == 403 && x2.code() == 400 && "MOTIF_OBLIGATOIRE".equals(x2.codeMetier()) && x3.code() == 200
                        && "ANNULE".equals(x3.json().path("statut").asText()) && x3.json().path("decisions").size() == 1,
                "Annulation : ni initiateur ni Administrateur 403 ; motif obligatoire ; ANNULE, décisions conservées [12.8]",
                "tiers " + x1.code() + ", sans motif " + x2.code() + ", Administrateur " + x3.code() + " " + x3.json().path("statut")
                        + ", décisions " + x3.json().path("decisions").size());
        verif("W-15", notifie(tDg, "CIRCUIT_ANNULE", d4, C4) && notifie(tV2, "CIRCUIT_ANNULE", d4, C4),
                "Annulation notifiée aux validateurs [12.9]", "");
        Rep o1 = g.json("POST", WF + "/documents/" + d4 + "/circuits", tAdmin, Map.of());
        Rep o2 = g.json("POST", WF + "/documents/" + d4 + "/circuits", tAdmin, Map.of());
        Rep o3 = g.json("POST", WF + "/documents/" + dL + "/circuits", tAdmin, Map.of());
        verif("W-16", o1.code() == 201 && "EN_COURS".equals(o1.json().path("statut").asText()) && o2.code() == 409
                        && "CIRCUIT_DEJA_OUVERT".equals(o2.codeMetier()) && o3.code() == 409 && "AUCUNE_REGLE".equals(o3.codeMetier()),
                "Nouveau circuit après annulation ; 409 CIRCUIT_DEJA_OUVERT ; 409 AUCUNE_REGLE sans règle [12.8]",
                o1.code() + " / " + o2.code() + " " + o2.codeMetier() + " / " + o3.code() + " " + o3.codeMetier());

        // ---------------------------------------------------------------- W-17 réaffectation manuelle tracée (Q1, D1)
        String d5 = deposer(tAdmin, t[1], "qae8-" + m + "-d5");
        JsonNode c5 = circuit(d5, tAdmin);
        String C5 = c5.path("id").asText();
        decider(tV2, C5, "VALIDE", null);
        String vDg = validateurId(c5, eDg), vV2 = validateurId(c5, eV2);
        Rep ra1 = g.json("PUT", WF + "/circuits/" + C5 + "/validateurs/" + vDg, tV2, Map.of("employeId", eV3, "motif", "Absence"));
        Rep ra2 = g.json("PUT", WF + "/circuits/" + C5 + "/validateurs/" + vV2, tAdmin, Map.of("employeId", eV3, "motif", "Absence"));
        Rep ra3 = g.json("PUT", WF + "/circuits/" + C5 + "/validateurs/" + vDg, tAdmin, Map.of("employeId", eV3, "motif", "Absence prolongée"));
        JsonNode vr = null;
        if (ra3.code() == 200) for (JsonNode x : ra3.json().path("validateurs")) if (x.path("id").asText().equals(vDg)) vr = x;
        Rep ra4 = decider(tV3, C5, "VALIDE", null);
        verif("W-17", ra1.code() == 403 && ra2.code() == 409 && "VALIDATEUR_DEJA_DECIDE".equals(ra2.codeMetier()) && ra3.code() == 200
                        && vr != null && eV3.equals(vr.path("employeId").asText()) && !vr.path("reaffecteDe").isNull()
                        && !vr.path("reaffectePar").isNull() && !vr.path("reaffecteLe").isNull()
                        && ra4.code() == 201 && "VALIDE".equals(ra4.json().path("statut").asText()),
                "Réaffectation par l'Administrateur seul, motif, ancien validateur et auteur conservés ; le nouveau décide [12.8, Q1/D1]",
                "tiers " + ra1.code() + ", déjà décidé " + ra2.code() + " " + ra2.codeMetier() + ", Administrateur " + ra3.code()
                        + (vr == null ? "" : " (de " + vr.path("reaffecteDe") + ")") + ", décision du remplaçant " + ra4.code() + " " + ra4.json().path("statut"));
        Rep an1 = g.get(WF + "/anomalies", tAdmin), an2 = g.get(WF + "/anomalies", tV2);
        verif("W-18", an1.code() == 200 && an2.code() == 403, "Validateurs défaillants : liste réservée à l'Administrateur [12.8, Q1]",
                "Administrateur " + an1.code() + ", standard " + an2.code());

        // ---------------------------------------------------------------- W-19 diffusion sans copie
        String uExt = meExt.path("id").asText();
        Rep avantDiff = g.get("/api/v1/documents/" + d1, tExt);
        Rep df0 = g.json("POST", WF + "/documents/" + d2 + "/diffusion", tAdmin, Map.of("utilisateurIds", List.of(uExt)));
        Rep df1 = g.json("POST", WF + "/documents/" + d1 + "/diffusion", tAdmin, Map.of("utilisateurIds", List.of(uExt)));
        Rep apresDiff = g.get("/api/v1/documents/" + d1, tExt);
        Rep modifExt = g.json("PUT", "/api/v1/documents/" + d1, tExt, Map.of("name", "qae8-" + m + "-pirate"));
        verif("W-19", avantDiff.code() == 404 && df0.code() == 409 && "DOCUMENT_NON_VALIDE".equals(df0.codeMetier())
                        && df1.code() == 200 && apresDiff.code() == 200 && d1.equals(apresDiff.json().path("id").asText())
                        && modifExt.code() == 403,
                "Diffusion d'un document validé : lecture accordée (même document, sans copie), écriture refusée ; document non validé 409 [12.8]",
                "avant " + avantDiff.code() + ", non validé " + df0.code() + " " + df0.codeMetier() + ", diffusion " + df1.code() + " "
                        + df1.corps() + ", après " + apresDiff.code() + ", écriture " + modifExt.code());

        // ---------------------------------------------------------------- W-20 règle modifiée : circuit figé
        g.json("PUT", WF + "/regles/" + R1, tAdmin, Map.of("name", "QA-E8-R1-" + m, "steps",
                List.of(Map.of("employeId", eDg, "label", "V1", "stepOrder", 1))));
        String d6 = deposer(tAdmin, t[1], "qae8-" + m + "-d6");
        JsonNode c6 = circuit(d6, tAdmin);
        JsonNode c2b = g.get(WF + "/circuits/" + C2, tAdmin).json();
        verif("W-20", c6.path("validateurs").size() == 1 && c2b.path("validateurs").size() == 2,
                "Règle modifiée : effet sur les seuls dépôts futurs, circuits existants figés [12.8, 4.5.4]",
                "nouveau circuit " + c6.path("validateurs").size() + " validateur(s), circuit existant " + c2b.path("validateurs").size());

        // ---------------------------------------------------------------- W-21 audit des actions du workflow
        List<String> manquants = new ArrayList<>();
        for (String[] a : new String[][]{{"CIRCUIT_OUVERT", null}, {"VALIDATION_APPROUVEE", null}, {"VALIDATION_REJETEE", null},
                {"CIRCUIT_ANNULE", null}, {"VALIDATEUR_REAFFECTE", null}, {"DOCUMENT_DIFFUSE", d1}, {"REGLE_WORKFLOW_RATTACHEE", null}}) {
            if (audit(a[0], a[1]).isEmpty()) manquants.add(a[0]);
        }
        JsonNode rej = audit("VALIDATION_REJETEE", null);
        boolean motif = rej.toString().contains("Montant non conforme");
        verif("W-21", manquants.isEmpty() && motif, "Chaque action du workflow est auditée (ouverture, décisions, annulation, réaffectation, diffusion, règle), motif compris [7.4.1]",
                "manquants " + manquants + ", motif du refus au journal " + motif);
        Rep hist = g.get(WF + "/historique", tV2);
        verif("W-22", hist.code() == 200 && hist.corps().contains(C1) && hist.corps().contains(C3), "Historique des décisions du validateur", "HTTP " + hist.code());

        // ================================================================ E8-API (D8, R-01/R-02)
        String idApp = null;
        for (JsonNode a : g.get("/api/v1/applications", tAdmin).json()) if (a.path("code").asText().equals("qa-workflow")) idApp = a.path("id").asText();
        if (idApp == null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("code", "qa-workflow");
            a.put("nom", "Recette qa workflow (intranet)");
            a.put("adressesAutorisees", List.of("127.0.0.1", "::1"));
            a.put("quotaMinute", 600);
            a.put("quotaJour", 100000);
            idApp = id(g.json("POST", "/api/v1/applications", tAdmin, a));
        }
        String[] cles = new String[3];
        Object[][] defs = {{true, List.of("CONSULTATION", "DEPOT", "WORKFLOW_PILOTAGE", "WORKFLOW_DECISION")},
                {true, List.of("CONSULTATION", "DEPOT")}, {false, List.of("CONSULTATION", "DEPOT", "WORKFLOW_PILOTAGE", "WORKFLOW_DECISION")}};
        for (int i = 0; i < 3; i++) {
            Rep k = g.json("POST", "/api/v1/applications/" + idApp + "/cles", tAdmin, Map.of("delegation", defs[i][0]));
            g.json("PUT", "/api/v1/cles-api/" + k.json().path("details").path("id").asText() + "/portee", tAdmin,
                    Map.of("portee", List.of(Map.of("noeudId", E, "operations", defs[i][1]), Map.of("noeudId", E2, "operations", defs[i][1]))));
            cles[i] = k.json().path("cle").asText();
        }
        // Remet la règle à deux validateurs pour la suite.
        g.json("PUT", WF + "/regles/" + R1, tAdmin, Map.of("name", "QA-E8-R1-" + m, "steps",
                List.of(Map.of("employeId", eDg, "label", "V1", "stepOrder", 1), Map.of("employeId", eV2, "label", "V2", "stepOrder", 2))));
        String d7 = deposer(tAdmin, t[1], "qae8-" + m + "-api");
        String C7 = circuit(d7, tAdmin).path("id").asText();

        Rep k1 = api(cles[0], null, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        verif("A-01", k1.code() == 403 && "DELEGATION_REQUISE".equals(k1.codeMetier()),
                "Workflow par application sans X-On-Behalf-Of : 403 DELEGATION_REQUISE (chaque action attribuée à une personne) [D8, 5.5]",
                "HTTP " + k1.code() + " " + k1.codeMetier());
        // Pilotage nominatif : désigner les validateurs (règle), la rattacher, annuler et rouvrir un circuit
        Rep k2 = api(cles[0], cAdmin, "POST", WF + "/regles", Map.of("name", "QA-E8-API-" + m, "steps",
                List.of(Map.of("employeId", eV2, "label", "Visa API", "stepOrder", 1))));
        verif("A-02", k2.code() == 201,
                "Désigner les validateurs par API (création d'une règle pour le compte d'un Administrateur, portée PILOTAGE) [D8, contrat E8-API « POST /regles : PILOTAGE »]",
                "HTTP " + k2.code() + " " + k2.codeMetier() + " " + (k2.code() == 201 ? "" : k2.json().path("detail").asText()));
        if (k2.code() == 201) {
            // ANO-E8-001 : modification et suppression par API ; refus hors des trois conditions ; double identité.
            String Rapi = k2.json().path("id").asText();
            Rep m1 = api(cles[0], cAdmin, "PUT", WF + "/regles/" + Rapi, Map.of("name", "QA-E8-API-" + m + "-v2", "steps",
                    List.of(Map.of("employeId", eV2, "label", "Visa API", "stepOrder", 1), Map.of("employeId", eDg, "label", "Visa DG", "stepOrder", 2))));
            Rep m2 = api(cles[0], cV2, "POST", WF + "/regles", Map.of("name", "QA-E8-API-intrus-" + m, "steps",
                    List.of(Map.of("employeId", eV2, "label", "x", "stepOrder", 1))));
            Rep m3 = api(cles[1], cAdmin, "POST", WF + "/regles", Map.of("name", "QA-E8-API-sansportee-" + m, "steps",
                    List.of(Map.of("employeId", eV2, "label", "x", "stepOrder", 1))));
            Rep m4 = api(cles[0], null, "POST", WF + "/regles", Map.of("name", "QA-E8-API-anonyme-" + m, "steps",
                    List.of(Map.of("employeId", eV2, "label", "x", "stepOrder", 1))));
            Rep m5 = api(cles[0], cAdmin, "DELETE", WF + "/regles/" + Rapi, Map.of());
            JsonNode cree = g.get("/api/v1/audit/evenements?action=WORKFLOW_CREE&objetId=" + Rapi, tAdmin).json().path("content").path(0);
            boolean doubleId = meAdmin.path("id").asText().equals(cree.path("acteurUtilisateurId").asText()) && idApp.equals(cree.path("acteurApplicationId").asText());
            verif("A-08", m1.code() == 200 && m1.json().path("steps").size() == 2 && m2.code() == 403 && m3.code() == 403 && m4.code() == 403
                            && (m5.code() == 204 || m5.code() == 200) && doubleId,
                    "Règles par API : modification et suppression pour un Administrateur délégué ; refus pour une personne sans gestion des référentiels, une clé sans PILOTAGE, sans délégation ; double identité au journal [D8, R-01, ANO-E8-001]",
                    "modification " + m1.code() + ", personne non habilitée " + m2.code() + ", clé sans PILOTAGE " + m3.code() + ", sans délégation " + m4.code()
                            + " " + m4.codeMetier() + ", suppression " + m5.code() + ", audit " + cree.path("acteurNom").asText() + " / application " + doubleId);
        }
        Rep k3 = api(cles[0], cAdmin, "PUT", WF + "/noeuds/" + E2 + "/regle", Map.of("regleId", R3));
        Rep k4 = api(cles[0], cAdmin, "POST", WF + "/documents/" + dL + "/circuits", Map.of());
        Rep k4b = k4.code() == 201 ? api(cles[0], cAdmin, "PUT", WF + "/circuits/" + k4.json().path("id").asText() + "/validateurs/"
                + validateurId(k4.json(), eDg), Map.of("employeId", eV2, "motif", "Réaffectation par l'intranet")) : k4;
        Rep k4c = k4.code() == 201 ? api(cles[0], cAdmin, "POST", WF + "/circuits/" + k4.json().path("id").asText() + "/annulation",
                Map.of("motif", "Annulé par l'intranet")) : k4;
        verif("A-07", k3.code() == 204 && k4.code() == 201 && "EN_COURS".equals(k4.json().path("statut").asText())
                        && k4b.code() == 200 && k4c.code() == 200 && "ANNULE".equals(k4c.json().path("statut").asText()),
                "Pilotage nominatif par API (délégation d'un Administrateur) : rattacher une règle, ouvrir un circuit, réaffecter un validateur, annuler [D8, R-01]",
                "rattachement " + k3.code() + ", ouverture " + k4.code() + ", réaffectation " + k4b.code() + " " + k4b.codeMetier()
                        + ", annulation " + k4c.code() + " " + k4c.json().path("statut"));
        // Décision pour le compte d'un validateur
        Rep k5 = api(cles[0], cV2, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        JsonNode dec = null;
        if (k5.code() == 201) for (JsonNode x : k5.json().path("decisions")) dec = x;
        verif("A-03", k5.code() == 201 && dec != null && idApp.equals(dec.path("applicationId").asText())
                        && "VALIDE".equals(etat(k5.json(), eV2)),
                "Décision pour le compte d'un validateur depuis l'application : attribuée à la personne, application tracée [D8, R-02]",
                "HTTP " + k5.code() + ", décision " + (dec == null ? k5.corps() : dec.toString()));
        JsonNode ev = audit("VALIDATION_APPROUVEE", null);
        boolean double_ = false;
        for (JsonNode e : ev) double_ |= meV2.path("id").asText().equals(e.path("acteurUtilisateurId").asText()) && idApp.equals(e.path("acteurApplicationId").asText());
        verif("A-04", double_, "Double identité au journal : acteur utilisateur (validateur) ET acteur application [7.4.1, 5.5, D8]", "");
        Rep k6 = api(cles[1], cDg, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        Rep k7 = api(cles[2], cDg, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        Rep k8 = api(cles[0], cV3, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        Rep k9 = api(cles[1], cAdmin, "POST", WF + "/documents/" + d7 + "/circuits", Map.of());
        verif("A-05", k6.code() == 403 && k7.code() == 403 && k8.code() == 403 && k9.code() == 403 && "EN_COURS".equals(circuit(d7, tAdmin).path("statut").asText()),
                "Clé sans portée WORKFLOW_DECISION / PILOTAGE, clé sans délégation, personne déléguée non validatrice : 403, rien n'est décidé [D8, 5.4]",
                "sans portée décision " + k6.code() + ", sans délégation " + k7.code() + ", non validateur " + k8.code() + " " + k8.codeMetier()
                        + ", sans portée pilotage " + k9.code());
        Rep k10 = api(cles[0], cDg, "POST", WF + "/circuits/" + C7 + "/decisions", Map.of("decision", "VALIDE"));
        verif("A-06", k10.code() == 201 && "VALIDE".equals(k10.json().path("statut").asText()),
                "Second validateur par API : circuit VALIDE", "HTTP " + k10.code() + " " + k10.json().path("statut"));
        System.exit(bilan("E8 workflow " + g.url));
    }

    static Rep api(String cle, String pourLeCompteDe, String methode, String chemin, Object corps) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-API-Key", cle);
        if (pourLeCompteDe != null) h.put("X-On-Behalf-Of", pourLeCompteDe);
        if (!methode.equals("GET")) h.put("Idempotency-Key", UUID.randomUUID().toString());
        return g.appel(methode, chemin, h, "application/json", JSON.writeValueAsBytes(corps));
    }
}
