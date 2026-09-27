import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E9 — API d'intégration par clé d'API (DAT V3 §5.1 à §5.5, §5.3.2).
 *
 * <p>Critère de sortie E9 : une application de test dépose, recherche et télécharge par clé
 * d'API, dans sa seule portée, et chaque appel est audité. S'y ajoutent Idempotency-Key, quotas,
 * adresses autorisées, cycle de vie des clés, délégation X-On-Behalf-Of et format problem+json.
 *
 * <p>Le script crée ses propres applications par l'API d'administration (codes suffixés d'un
 * marqueur d'exécution). Prérequis : jeu d'habilitations de la recette E3 (TIERS habilité sur
 * « Comptabilité » seulement). Variables : GED_URL, GED_RECETTE_MOT_DE_PASSE, comptes GED_E3_*,
 * GED_DONNEES.
 */
public class RecetteApi extends ClientGed {

    RecetteApi(String url) {
        super(url);
    }

    static RecetteApi g;
    static String tAdmin;

    static Map<String, String> cle(String secret, String... autres) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-API-Key", secret);
        for (int i = 0; i + 1 < autres.length; i += 2) h.put(autres[i], autres[i + 1]);
        return h;
    }

    static boolean problemJson(Rep r) {
        return r.entetes().firstValue("Content-Type").orElse("").startsWith("application/problem+json")
                && !r.codeMetier().isBlank();
    }

    /** Crée une application et une clé ; renvoie {idApplication, idCle, secret}. */
    static String[] application(String code, List<String> adresses, Integer quotaMinute, boolean delegation) throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("code", code);
        a.put("nom", "Recette qa " + code);
        a.put("description", "Application de recette qa (E9)");
        a.put("adressesAutorisees", adresses);
        a.put("quotaMinute", quotaMinute);
        a.put("quotaJour", 100000);
        Rep app = g.json("POST", "/api/v1/applications", tAdmin, a);
        if (app.code() != 201) throw new IllegalStateException("application refusée : HTTP " + app.code() + " " + app.corps());
        String idApp = app.json().path("id").asText();
        Rep k = g.json("POST", "/api/v1/applications/" + idApp + "/cles", tAdmin, Map.of("delegation", delegation));
        if (k.code() != 201) throw new IllegalStateException("clé refusée : HTTP " + k.code() + " " + k.corps());
        return new String[]{idApp, k.json().path("details").path("id").asText(), k.json().path("cle").asText()};
    }

    static Rep portee(String idCle, Map<String, List<String>> parNoeud) throws Exception {
        List<Map<String, Object>> p = new java.util.ArrayList<>();
        parNoeud.forEach((n, ops) -> p.add(Map.of("noeudId", n, "operations", ops)));
        return g.json("PUT", "/api/v1/cles-api/" + idCle + "/portee", tAdmin, Map.of("portee", p));
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteApi(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path pdf = Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf");
        byte[] octets = Files.readAllBytes(pdf);
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String cTiers = env("GED_E3_TIERS", "yalaoui");
        String uTiers = null;
        for (JsonNode u : g.get("/api/v1/admin/utilisateurs", tAdmin).json())
            if (u.path("identifiant").asText().equalsIgnoreCase(cTiers)) uTiers = u.path("id").asText();
        String m = Long.toString(System.currentTimeMillis(), 36);
        Instant t0 = Instant.now().minusSeconds(2);

        Map<String, String> noeuds = new LinkedHashMap<>();
        pile(g.get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        String A = noeuds.get("Comptabilité"), B = noeuds.get("Ressources Humaines");
        String typeA = null, typeB = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content")) {
            if (t.path("code").asText().equals("TD-FACT")) typeA = t.path("id").asText();
            if (t.path("code").asText().equals("TD-QA-RH")) typeB = t.path("id").asText();
        }
        // Témoin déposé en B par un utilisateur (hors portée de la clé principale)
        String docB = g.deposer(tDep, pdf, "qae9-" + m + "-temoin-b", typeB, null).json().path("id").asText();

        // ---------------- application principale : portée A (dépôt, consultation, recherche)
        String[] app = application("qa-bo-" + m, List.of("127.0.0.1", "::1"), 600, false);
        Rep por = portee(app[1], Map.of(A, List.of("DEPOT", "CONSULTATION", "RECHERCHE")));
        String secret = app[2];
        verif("E9-01", secret.matches("^ged_dev_[A-Za-z0-9]+_[A-Za-z0-9_-]{30,}$") && por.code() == 200,
                "Clé générée au format ged_<env>_<identifiant>_<secret>, portée par nœud et opération [5.4]",
                "préfixe " + secret.substring(0, Math.min(16, secret.length())) + "…, portée HTTP " + por.code());
        String vue = g.get("/api/v1/applications/" + app[0], tAdmin).corps();
        verif("E9-02", !vue.contains(secret) && !vue.contains(secret.substring(secret.lastIndexOf('_') + 1)),
                "Secret affiché une seule fois : absent de toute consultation ultérieure [5.4]", "");

        // Dépôt avec Idempotency-Key, rejeu, conflit, absence
        String cleIdem = UUID.randomUUID().toString();
        Map<String, String> champs = Map.of("name", "qae9-" + m + "-api", "typeDocumentId", typeA);
        Rep d1 = g.deposer(cle(secret, "Idempotency-Key", cleIdem), octets, "api.pdf", "application/pdf", champs);
        String doc = d1.json().path("id").asText();
        Rep d2 = g.deposer(cle(secret, "Idempotency-Key", cleIdem), octets, "api.pdf", "application/pdf", champs);
        long exemplaires = g.get("/api/v1/documents?size=200&search=" + enc("qae9-" + m + "-api"), tAdmin).json().path("total").asLong(-1);
        verif("E9-03", d1.code() / 100 == 2 && d2.code() == d1.code() && d2.json().path("id").asText().equals(doc)
                        && d2.entetes().firstValue("Idempotency-Replayed").isPresent() && exemplaires == 1,
                "Dépôt par clé d'API ; rejeu de la même Idempotency-Key : réponse initiale, aucun doublon [5.3, 5.3.2]",
                "1er " + d1.code() + ", rejeu " + d2.code() + " (Idempotency-Replayed " + d2.entetes().firstValue("Idempotency-Replayed").orElse("absent")
                        + "), documents de ce nom : " + exemplaires);
        Rep d3 = g.deposer(cle(secret, "Idempotency-Key", cleIdem), octets, "api.pdf", "application/pdf",
                Map.of("name", "qae9-" + m + "-autre", "typeDocumentId", typeA));
        verif("E9-04", d3.code() == 422 && d3.codeMetier().equals("IDEMPOTENCE_CONFLIT") && problemJson(d3),
                "Même Idempotency-Key, contenu différent : 422 IDEMPOTENCE_CONFLIT en problem+json [5.3.2]", "HTTP " + d3.code() + " " + d3.codeMetier());
        Rep d4 = g.deposer(cle(secret), octets, "api.pdf", "application/pdf", Map.of("name", "qae9-" + m + "-sans", "typeDocumentId", typeA));
        Rep d5 = g.deposer(Map.of("Authorization", "Bearer " + tDep), octets, "api.pdf", "application/pdf", Map.of("name", "qae9-" + m + "-user", "typeDocumentId", typeA));
        verif("E9-05", d4.code() == 400 && d5.code() == 400, "Création sans Idempotency-Key refusée (400), par clé d'API comme par un utilisateur [5.3.2]",
                "clé " + d4.code() + " " + d4.codeMetier() + ", utilisateur " + d5.code() + " " + d5.codeMetier());

        // Recherche, téléchargement, hors portée
        Rep rech = g.appel("GET", "/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", cle(secret), null, null);
        boolean voitB = rech.corps().contains(docB);
        verif("E9-06", rech.code() == 200 && !voitB, "Recherche par clé d'API filtrée par sa portée (témoin de B absent) [5.2, 5.3.1]",
                "HTTP " + rech.code() + ", total " + rech.json().path("total").asText() + ", témoin B présent " + voitB);
        Rep dl = g.appel("GET", "/api/v1/documents/" + doc + "/download", cle(secret), null, null);
        verif("E9-07", dl.code() == 200 && sha256(dl.octets()).equals(sha256(octets)), "Téléchargement par clé d'API dans la portée, identique [5.3]", "HTTP " + dl.code());
        Rep horsB = g.appel("GET", "/api/v1/documents/" + docB, cle(secret), null, null);
        Rep inconnu = g.appel("GET", "/api/v1/documents/" + UUID.randomUUID(), cle(secret), null, null);
        verif("E9-08", horsB.code() == 404 && inconnu.code() == 404 && horsB.codeMetier().equals(inconnu.codeMetier()),
                "Document hors portée : 404 indiscernable d'un inexistant [5.2, 6.2.3 A01]", "hors portée " + horsB.code() + ", inexistant " + inconnu.code());
        Rep depB = g.deposer(cle(secret, "Idempotency-Key", UUID.randomUUID().toString()), octets, "b.pdf", "application/pdf",
                Map.of("name", "qae9-" + m + "-dans-b", "typeDocumentId", typeB));
        verif("E9-09", depB.code() == 403 || depB.code() == 404, "Dépôt hors portée refusé (403/404) [5.4]", "HTTP " + depB.code() + " " + depB.codeMetier());
        Rep vers = g.appel("POST", "/api/v1/documents/" + doc + "/versions", cle(secret, "Idempotency-Key", UUID.randomUUID().toString()),
                "multipart/form-data; boundary=x", "--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"v.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\r\n--x--\r\n".getBytes());
        verif("E9-10", vers.code() == 403, "Opération non accordée par la portée (versement) : 403 [5.4]", "HTTP " + vers.code() + " " + vers.codeMetier());
        Rep admin = g.appel("GET", "/api/v1/admin/habilitations", cle(secret), null, null);
        Rep clesParCle = g.appel("GET", "/api/v1/applications", cle(secret), null, null);
        verif("E9-11", admin.code() / 100 == 4 && clesParCle.code() / 100 == 4, "Routes d'administration fermées à une clé d'API [5.4]", "admin " + admin.code() + ", applications " + clesParCle.code());

        // Audit : chaque appel attribuable à l'application
        Thread.sleep(1000);
        JsonNode aud = g.get("/api/v1/audit/evenements?taille=200&application=" + app[0] + "&du=" + t0, tAdmin).json().path("content");
        boolean depose = false, appel = false;
        for (JsonNode e : aud) {
            depose |= e.path("action").asText().equals("DOCUMENT_DEPOSE") && e.path("objetId").asText().equals(doc);
            appel |= e.path("action").asText().equals("APPEL_API");
        }
        verif("E9-12", depose && appel, "Chaque appel par clé d'API est audité et attribué à l'application (acteur_application_id) [5.2, 5.4, critère E9]",
                aud.size() + " événement(s) de l'application ; dépôt " + depose + ", APPEL_API " + appel);

        // Quotas, adresses, environnement
        String[] petit = application("qa-quota-" + m, List.of("127.0.0.1", "::1"), 3, false);
        portee(petit[1], Map.of(A, List.of("CONSULTATION")));
        Rep q = null;
        for (int i = 0; i < 4; i++) q = g.appel("GET", "/api/v1/documents?size=1", cle(petit[2]), null, null);
        verif("E9-13", q.code() == 429 && q.entetes().firstValue("Retry-After").isPresent() && problemJson(q),
                "Quota par minute dépassé : 429 avec Retry-After, en problem+json [5.4, 5.3.2]",
                "4e appel HTTP " + q.code() + ", Retry-After " + q.entetes().firstValue("Retry-After").orElse("absent") + ", " + q.codeMetier());
        String[] loin = application("qa-ip-" + m, List.of("10.9.9.9"), 600, false);
        portee(loin[1], Map.of(A, List.of("CONSULTATION")));
        Rep ip = g.appel("GET", "/api/v1/documents?size=1", cle(loin[2]), null, null);
        verif("E9-14", ip.code() == 403 || ip.code() == 401, "Appel depuis une adresse non autorisée refusé [5.4]", "HTTP " + ip.code() + " " + ip.codeMetier());
        String autreEnv = secret.replaceFirst("^ged_dev_", "ged_prod_");
        Rep env = g.appel("GET", "/api/v1/documents?size=1", cle(autreEnv), null, null);
        Rep faux = g.appel("GET", "/api/v1/documents?size=1", cle(secret.substring(0, secret.length() - 3) + "abc"), null, null);
        verif("E9-15", env.code() == 401 && faux.code() == 401, "Clé d'un autre environnement ou secret faux : 401 [5.4]", "autre env " + env.code() + ", secret faux " + faux.code());

        // Régénération (chevauchement) puis révocation
        Rep reg = g.appel("POST", "/api/v1/cles-api/" + app[1] + "/regeneration", tAdmin, null, null);
        String nouvelle = reg.json().path("cle").asText();
        Rep ancienne = g.appel("GET", "/api/v1/documents/" + doc, cle(secret), null, null);
        Rep neuve = g.appel("GET", "/api/v1/documents/" + doc, cle(nouvelle), null, null);
        verif("E9-16", reg.code() == 201 && ancienne.code() == 200 && neuve.code() == 200,
                "Régénération : nouvelle clé active, ancienne encore valide pendant le chevauchement, portée recopiée [5.4]",
                "régénération " + reg.code() + ", ancienne " + ancienne.code() + ", nouvelle " + neuve.code());
        Rep rev = g.json("POST", "/api/v1/cles-api/" + app[1] + "/revocation", tAdmin, Map.of("motif", "Recette qa"));
        Rep apresRev = g.appel("GET", "/api/v1/documents/" + doc, cle(secret), null, null);
        verif("E9-17", rev.code() == 200 && apresRev.code() == 401, "Révocation : clé refusée immédiatement [5.4]", "révocation " + rev.code() + ", appel " + apresRev.code());

        // ---------------- délégation X-On-Behalf-Of (§5.5)
        String[] del = application("qa-deleg-" + m, List.of("127.0.0.1", "::1"), 600, true);
        portee(del[1], Map.of(A, List.of("DEPOT", "CONSULTATION", "RECHERCHE"), B, List.of("CONSULTATION", "RECHERCHE")));
        Rep sansHeader = g.appel("GET", "/api/v1/documents/" + docB, cle(del[2]), null, null);
        Rep avecHeader = g.appel("GET", "/api/v1/documents/" + docB, cle(del[2], "X-On-Behalf-Of", cTiers), null, null);
        verif("E9-18", sansHeader.code() == 200 && avecHeader.code() == 404,
                "Délégation en lecture : intersection des droits de la clé (A et B) et de l'utilisateur (A seul) → document de B invisible [5.5]",
                "sans délégation " + sansHeader.code() + ", pour le compte de " + cTiers + " " + avecHeader.code());
        Rep depDel = g.deposer(cle(del[2], "X-On-Behalf-Of", cTiers, "Idempotency-Key", UUID.randomUUID().toString()), octets, "d.pdf", "application/pdf",
                Map.of("name", "qae9-" + m + "-delegue", "typeDocumentId", typeA));
        String docDel = depDel.json().path("id").asText();
        String deposant = depDel.json().path("createdBy").asText();
        Thread.sleep(1000);
        JsonNode evDel = g.get("/api/v1/audit/evenements?action=DOCUMENT_DEPOSE&objetId=" + docDel, tAdmin).json().path("content").path(0);
        verif("E9-19", depDel.code() / 100 == 2 && evDel.path("acteurUtilisateurId").asText().equals(uTiers) && evDel.path("acteurApplicationId").asText().equals(del[0]),
                "Délégation en écriture : l'utilisateur délégué est le déposant, double identité au journal [5.5, 7.4.1]",
                "HTTP " + depDel.code() + ", déposant « " + deposant + " », audit utilisateur " + evDel.path("acteurUtilisateurId").asText()
                        + " / application " + evDel.path("acteurApplicationId").asText());
        Rep inconnuDel = g.appel("GET", "/api/v1/documents/" + docDel, cle(del[2], "X-On-Behalf-Of", "qapersonne" + m), null, null);
        verif("E9-20", inconnuDel.code() == 422 && inconnuDel.codeMetier().equals("IDENTITE_DELEGUEE_INVALIDE") && problemJson(inconnuDel),
                "Identité déléguée inconnue : 422 IDENTITE_DELEGUEE_INVALIDE [5.5]", "HTTP " + inconnuDel.code() + " " + inconnuDel.codeMetier());
        String[] sansDel = application("qa-sansdel-" + m, List.of("127.0.0.1", "::1"), 600, false);
        portee(sansDel[1], Map.of(A, List.of("CONSULTATION")));
        Rep interdit = g.appel("GET", "/api/v1/documents/" + doc, cle(sansDel[2], "X-On-Behalf-Of", cTiers), null, null);
        verif("E9-21", interdit.code() == 403, "X-On-Behalf-Of avec une clé sans attribut « délégation » : 403 [5.5]", "HTTP " + interdit.code() + " " + interdit.codeMetier());
        Rep desactive = g.appel("GET", "/api/v1/documents/" + docDel, cle(del[2], "X-On-Behalf-Of", env("GED_E9_COMPTE_DESACTIVE", "otazi")), null, null);
        res("E9-22", desactive.code() == 422 ? "OK" : "AVERT", "Délégation pour un compte désactivé dans l'annuaire : 422 attendu par le §5.5 (point ouvert D1/QR9)",
                "HTTP " + desactive.code() + " " + desactive.codeMetier() + (desactive.code() == 422 ? "" : " — l'option verifier-compte-annuaire est désactivée par défaut"));

        // ---------------- format des erreurs et contrat
        Rep e404 = g.get("/api/v1/documents/" + UUID.randomUUID(), tDep);
        Rep e400 = g.json("POST", "/api/v1/admin/habilitations", tAdmin, Map.of());
        verif("E9-23", problemJson(e404) && problemJson(e400) && e400.json().has("status"),
                "Erreurs au format application/problem+json (RFC 7807) avec code métier stable [5.3.2]",
                "404 " + e404.entetes().firstValue("Content-Type").orElse("") + " " + e404.codeMetier() + " ; 400 " + e400.codeMetier());
        Rep oa = g.get("/v3/api-docs", tDep);
        String spec = oa.corps();
        verif("E9-24", oa.code() == 200 && spec.contains("X-API-Key") && spec.contains("Idempotency-Key") && spec.contains("X-On-Behalf-Of")
                        && spec.contains("application/problem+json") && spec.contains("Retry-After"),
                "Spécification OpenAPI 3 : clé d'API, Idempotency-Key, X-On-Behalf-Of, problem+json, Retry-After documentés [5.3]",
                "HTTP " + oa.code() + ", " + spec.length() + " caractères");
        List<String> manquants = new java.util.ArrayList<>();
        for (String c : List.of("/api/v1/noeuds/{id}/dossiers", "/api/v1/recherches", "/api/v1/documents/{id}/contenu", "/api/v1/documents/{id}/droits"))
            if (!spec.contains(c)) manquants.add(c);
        res("E9-25", manquants.isEmpty() ? "OK" : "AVERT", "Chemins du contrat §5.3.1 exposés",
                manquants.isEmpty() ? "" : "absents : " + manquants + " (T-042 / P-06 : à faire)");

        info("applications de recette conservées (suffixe " + m + ")");
        System.exit(bilan("E9 API d'intégration " + g.url));
    }

    static void pile(JsonNode n, Map<String, String> acc) {
        for (JsonNode x : n) {
            acc.put(x.path("name").asText(), x.path("id").asText());
            pile(x.path("children"), acc);
        }
    }
}
