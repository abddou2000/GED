import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette du tour 4 (vague 11) contre une instance : T-050 (pagination homogène des recherches et de la liste
 * « à traiter », §5.3.2) et P-08 (champs inconnus des recherches ignorés et signalés par l'en-tête
 * {@code GED-Champs-Ignores} au lieu du 400 {@code PARAMETRE_INCONNU}, en-tête exposé en CORS, §5.3.2).
 *
 * <p>Corrections recettées : T-050 dev3 {@code 4bc6f47}, dev5 {@code f8b8bfb} ; P-08 dev3 {@code d47c3de}.
 * Variables : GED_URL, GED_RECETTE_MOT_DE_PASSE, GED_E3_ADMIN (défaut sbennani), GED_R4_TERME (défaut
 * « convention » : terme présent dans le fonds de recette), GED_R4_ORIGINE (défaut http://localhost:4200, origine
 * admise par le profil dev).
 */
public class RecetteTour4 extends ClientGed {

    RecetteTour4(String url) {
        super(url);
    }

    static RecetteTour4 g;
    static String jeton;
    static final String ENTETE = "GED-Champs-Ignores";

    static Rep post(String chemin, String corps) throws Exception {
        return g.appel("POST", chemin, jeton, "application/json", corps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String ign(Rep r) {
        return r.entetes().firstValue(ENTETE).orElse("");
    }

    static int taille(JsonNode j, String liste) {
        return j.path(liste).size();
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteTour4(env("GED_URL", "http://localhost:18084"));
        jeton = g.connecter(env("GED_E3_ADMIN", "sbennani"), env("GED_RECETTE_MOT_DE_PASSE", null));
        String terme = env("GED_R4_TERME", "convention");
        String origine = env("GED_R4_ORIGINE", "http://localhost:4200");
        String type = g.get("/api/v1/type-documents/for-select", jeton).json().path(0).path("id").asText();
        String pt = "/api/v1/recherche/plein-texte?q=" + enc(terme);

        // ============================================================ T-050 pagination homogène
        // 1. GET /recherche/plein-texte : 50 par défaut (et non 20), size lu, alias taille, plafond 200.
        JsonNode d1 = g.get(pt, jeton).json();
        JsonNode s1 = g.get(pt + "&size=2&page=0", jeton).json();
        JsonNode t1 = g.get(pt + "&taille=2&page=0", jeton).json();
        JsonNode p1 = g.get(pt + "&size=100000", jeton).json();
        verif("T050-R4-01", d1.path("size").asInt() == 50 && d1.path("taille").asInt() == 50
                        && s1.path("size").asInt() == 2 && taille(s1, "resultats") <= 2
                        && t1.path("size").asInt() == 2 && taille(t1, "resultats") <= 2 && p1.path("size").asInt() == 200
                        && d1.path("total").asLong() > 2,
                "GET /recherche/plein-texte : 50 par défaut, size et alias taille lus, plafond 200 [5.3.2, T-050]",
                "défaut " + d1.path("size") + "/" + d1.path("taille") + " (total " + d1.path("total") + "), size=2 → " + s1.path("size") + " ("
                        + taille(s1, "resultats") + " résultats), taille=2 → " + t1.path("size") + ", size=100000 → " + p1.path("size"));

        // 2. POST /recherches (contrat §5.3.1), sans et avec texte.
        StringBuilder det2 = new StringBuilder();
        boolean ok2 = true;
        for (String texte : new String[]{"", ",\"texte\":\"" + terme + "\""}) {
            String base = "{\"typeDocumentId\":\"" + type + "\"" + texte;
            JsonNode d = post("/api/v1/recherches", base + "}").json();
            JsonNode s = post("/api/v1/recherches", base + ",\"size\":2}").json();
            JsonNode t = post("/api/v1/recherches", base + ",\"taille\":2}").json();
            JsonNode b = post("/api/v1/recherches", base + ",\"size\":1,\"taille\":2}").json();
            JsonNode p = post("/api/v1/recherches", base + ",\"size\":100000}").json();
            boolean ok = d.path("size").asInt() == 50 && taille(s, "resultats") <= 2 && taille(t, "resultats") <= 2
                    && taille(b, "resultats") <= 1 && p.path("size").asInt() == 200;
            ok2 &= ok;
            det2.append(texte.isEmpty() ? "sans texte : " : " ; avec texte : ").append("défaut ").append(d.path("size"))
                    .append(" (total ").append(d.path("total")).append("), size=2 → ").append(taille(s, "resultats"))
                    .append(", taille=2 → ").append(taille(t, "resultats")).append(", size=1+taille=2 → ").append(taille(b, "resultats"))
                    .append(", size=100000 → ").append(p.path("size"));
        }
        verif("T050-R4-02", ok2, "POST /recherches (SQL et plein texte) : 50 par défaut, size et taille, size l'emporte, plafond 200 [5.3.1, 5.3.2, T-050]", det2.toString());

        // 3. POST /documents/recherche : alias taille.
        JsonNode d3 = post("/api/v1/documents/recherche", "{}").json();
        JsonNode t3 = post("/api/v1/documents/recherche", "{\"taille\":2}").json();
        JsonNode b3 = post("/api/v1/documents/recherche", "{\"size\":1,\"taille\":2}").json();
        JsonNode p3 = post("/api/v1/documents/recherche", "{\"size\":100000}").json();
        verif("T050-R4-03", d3.path("size").asInt() == 50 && taille(t3, "content") == Math.min(2, d3.path("total").asInt())
                        && taille(b3, "content") == 1 && p3.path("size").asInt() == 200,
                "POST /documents/recherche : 50 par défaut, alias taille, size l'emporte, plafond 200 [5.3.2, T-050]",
                "défaut " + d3.path("size") + " (total " + d3.path("total") + "), taille=2 → " + taille(t3, "content") + ", size=1+taille=2 → "
                        + taille(b3, "content") + ", size=100000 → " + p3.path("size"));

        // 4. POST /indexation/recherche : size en plus de taille.
        String parType = "{\"typeDocumentId\":\"" + type + "\"";
        Rep r4d = post("/api/v1/indexation/recherche", parType + "}");
        JsonNode s4 = post("/api/v1/indexation/recherche", parType + ",\"size\":2}").json();
        JsonNode t4 = post("/api/v1/indexation/recherche", parType + ",\"taille\":2}").json();
        JsonNode p4 = post("/api/v1/indexation/recherche", parType + ",\"taille\":100000}").json();
        verif("T050-R4-04", r4d.json().path("size").asInt() == 50 && s4.path("size").asInt() == 2 && t4.path("size").asInt() == 2
                        && p4.path("size").asInt() == 200 && ign(r4d).isEmpty(),
                "POST /indexation/recherche : 50 par défaut, size et taille, plafond 200 [5.3.2, T-050]",
                "défaut " + r4d.json().path("size") + " (total " + r4d.json().path("total") + "), size=2 → " + s4.path("size") + ", taille=2 → "
                        + t4.path("size") + ", taille=100000 → " + p4.path("size"));

        // 5. GET /workflow/a-traiter : 50 par défaut (et non 20), plafond 200 (et non 100), alias taille, page négative 400.
        JsonNode d5 = g.get("/api/v1/workflow/a-traiter", jeton).json();
        JsonNode p5 = g.get("/api/v1/workflow/a-traiter?size=500", jeton).json();
        JsonNode t5 = g.get("/api/v1/workflow/a-traiter?taille=3", jeton).json();
        Rep n5 = g.get("/api/v1/workflow/a-traiter?page=-1", jeton);
        verif("T050-R4-05", d5.path("size").asInt() == 50 && p5.path("size").asInt() == 200 && t5.path("size").asInt() == 3 && n5.code() == 400,
                "GET /workflow/a-traiter : 50 par défaut, plafond 200, alias taille, page négative refusée [5.3.2, T-050]",
                "défaut " + d5.path("size") + ", size=500 → " + p5.path("size") + ", taille=3 → " + t5.path("size") + ", page=-1 → " + n5.code() + " " + n5.codeMetier());

        // ============================================================ P-08 champs inconnus ignorés et signalés
        Rep a1 = post("/api/v1/documents/recherche", "{\"confidentialit\":\"PRIVE\"}");
        verif("P08-R4-01", a1.code() == 200 && ign(a1).equals("confidentialit") && a1.json().path("total").asLong() == d3.path("total").asLong(),
                "POST /documents/recherche : champ inconnu ignoré (200, aucun filtre appliqué) et signalé dans GED-Champs-Ignores [5.3.2, P-08]",
                "HTTP " + a1.code() + ", en-tête « " + ign(a1) + " », total " + a1.json().path("total") + " (sans critère : " + d3.path("total") + ")");

        Rep a2 = post("/api/v1/recherches", "{\"deposant\":\"x\",\"confidentialité\":{\"a\":[1]}}");
        Rep a3 = post("/api/v1/recherches", "{\"criteres\":[{\"indexFieldId\":\"" + UUID.randomUUID() + "\",\"valeurr\":\"x\"}]}");
        verif("P08-R4-02", a2.code() == 200 && ign(a2).equals("deposant, confidentialit%C3%A9") && a3.code() == 200 && ign(a3).equals("criteres[0].valeurr"),
                "POST /recherches : plusieurs champs inconnus (nom accentué encodé), champ inconnu imbriqué avec son chemin [5.3.2, P-08]",
                "HTTP " + a2.code() + " « " + ign(a2) + " » ; imbriqué HTTP " + a3.code() + " « " + ign(a3) + " »");

        Rep a4 = g.get(pt + "&dateDu=2026-09-01", jeton);
        Rep a5 = post("/api/v1/indexation/recherche", "{\"taile\":10}");
        verif("P08-R4-03", a4.code() == 200 && ign(a4).equals("dateDu") && a4.json().path("total").asLong() == d1.path("total").asLong()
                        && a5.code() == 200 && ign(a5).equals("taile"),
                "GET /recherche/plein-texte (paramètre d'URL inconnu) et POST /indexation/recherche : ignorés et signalés [5.3.2, P-08]",
                "plein texte HTTP " + a4.code() + " « " + ign(a4) + " » total " + a4.json().path("total") + " (sans : " + d1.path("total") + ") ; indexation HTTP "
                        + a5.code() + " « " + ign(a5) + " »");

        // Tout est connu : pas d'en-tête ; une valeur invalide reste un 400 (ce n'est pas un champ inconnu).
        Rep c1 = post("/api/v1/recherches", "{\"confidentialite\":\"PRIVE\"}");
        Rep c2 = post("/api/v1/recherches", "{\"confidentialite\":\"SECRET\"}");
        Rep c3 = g.get(pt + "&dateDocumentDu=2026-10-01&dateDocumentAu=2026-09-01", jeton);
        Rep c4 = post("/api/v1/documents/recherche", "{\"dateDepotDu\":\"2026-04-01\",\"dateDepotAu\":\"2026-03-01\"}");
        verif("P08-R4-04", c1.code() == 200 && ign(c1).isEmpty() && ign(s1Rep(pt)).isEmpty() && c2.code() == 400 && c3.code() == 400 && c4.code() == 400,
                "Champs tous connus : aucun en-tête ; valeur hors liste ou bornes inversées : toujours 400 [5.3.2, P-08]",
                "connus " + c1.code() + " « " + ign(c1) + " » ; SECRET " + c2.code() + " " + c2.codeMetier() + " ; bornes inversées plein texte " + c3.code()
                        + ", dépôt " + c4.code());

        // CORS : l'en-tête est lisible par un frontend d'une autre origine (écrans « Critère non appliqué »).
        HttpClient brut = HttpClient.newHttpClient();
        HttpResponse<String> pre = brut.send(HttpRequest.newBuilder(URI.create(g.url + "/api/v1/documents/recherche"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", origine)
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type").build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> reel = brut.send(HttpRequest.newBuilder(URI.create(g.url + "/api/v1/documents/recherche"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"confidentialit\":\"PRIVE\"}")).header("Origin", origine)
                .header("Authorization", "Bearer " + jeton).header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        String expose = reel.headers().firstValue("Access-Control-Expose-Headers").orElse("");
        HttpResponse<String> autre = brut.send(HttpRequest.newBuilder(URI.create(g.url + "/api/v1/documents/recherche"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "https://intrus.example")
                .header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());
        verif("P08-R4-05", pre.statusCode() == 200 && reel.statusCode() == 200 && expose.contains(ENTETE)
                        && reel.headers().firstValue(ENTETE).orElse("").equals("confidentialit") && autre.statusCode() == 403,
                "CORS : GED-Champs-Ignores exposé (Access-Control-Expose-Headers) à l'origine admise ; origine étrangère refusée [5.3.2, P-08]",
                "pré-vol " + pre.statusCode() + ", requête " + reel.statusCode() + ", Expose-Headers « " + expose + " », Allow-Origin « "
                        + reel.headers().firstValue("Access-Control-Allow-Origin").orElse("") + " » ; origine étrangère " + autre.statusCode());

        // Documentation OpenAPI : l'en-tête et la règle sont décrits.
        String doc = g.get("/v3/api-docs", jeton).corps();
        verif("P08-R4-06", doc.contains(ENTETE) && !doc.contains("PARAMETRE_INCONNU"),
                "Spécification OpenAPI : en-tête GED-Champs-Ignores décrit, plus de 400 PARAMETRE_INCONNU annoncé [5.3.2, P-08]",
                "mention de l'en-tête " + doc.contains(ENTETE) + ", PARAMETRE_INCONNU encore cité " + doc.contains("PARAMETRE_INCONNU"));

        System.exit(bilan("Tour 4 : T-050, P-08 " + g.url));
    }

    static Rep s1Rep(String pt) throws Exception {
        return g.get(pt + "&size=2", jeton);
    }
}
