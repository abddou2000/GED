import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette des chemins exacts du contrat d'API (DAT V3 §5.3.1, conventions §5.3.2 ; P-06, T-042,
 * T-044) : création de dossier, dépôt par application, recherche (critère canal, pagination),
 * téléchargement par {@code /contenu}, consultation des droits. Chaque chemin est éprouvé sur son
 * droit requis, son rejeu (Idempotency-Key) et le 404 indiscernable (P5).
 *
 * <p>Prérequis : jeu d'habilitations de la recette E3 (comptes GED_E3_*).
 */
public class RecetteContrat extends ClientGed {

    RecetteContrat(String url) {
        super(url);
    }

    static String masquer(String corps) {
        return corps.replaceAll("\"(instance|traceId|timestamp|horodatage)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"*\"")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "*");
    }

    public static void main(String[] args) throws Exception {
        RecetteContrat g = new RecetteContrat(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        byte[] pdf = Files.readAllBytes(Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf"));
        String tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String tTiers = g.connecter(env("GED_E3_TIERS", "yalaoui"), mdp);
        String cSans = env("GED_E3_SANS_DROIT", "nidrissi");
        String tSans = g.connecter(cSans, mdp);
        String A = null, typeA = null;
        for (JsonNode x : g.get("/api/v1/workspaces/tree", tAdmin).json()) if (x.path("name").asText().equals("Comptabilité")) A = x.path("id").asText();
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content"))
            if (t.path("code").asText().equals("TD-FACT")) typeA = t.path("id").asText();
        String m = Long.toString(System.currentTimeMillis(), 36);
        String absent = UUID.randomUUID().toString();

        // ============ Création de dossier : POST /noeuds/{id}/dossiers (Déposer sur le parent, Idempotency-Key)
        String cle = UUID.randomUUID().toString();
        byte[] corps = JSON.writeValueAsBytes(Map.of("nom", "qacontrat-" + m, "description", "recette §5.3.1"));
        Map<String, String> h = new LinkedHashMap<>(Map.of("Authorization", "Bearer " + tDep, "Idempotency-Key", cle));
        Rep c1 = g.appel("POST", "/api/v1/noeuds/" + A + "/dossiers", h, "application/json", corps);
        String dossier = c1.json().path("id").asText();
        Rep c1b = g.appel("POST", "/api/v1/noeuds/" + A + "/dossiers", h, "application/json", corps);
        Rep c1c = g.appel("POST", "/api/v1/noeuds/" + A + "/dossiers", h, "application/json",
                JSON.writeValueAsBytes(Map.of("nom", "qacontrat-" + m + "-autre")));
        Rep c1d = g.appel("POST", "/api/v1/noeuds/" + A + "/dossiers", Map.of("Authorization", "Bearer " + tDep), "application/json", corps);
        boolean parent = false;
        for (JsonNode x : g.get("/api/v1/workspaces/tree", tDep).json())
            if (x.path("id").asText().equals(A)) parent = x.toString().contains(dossier);
        verif("C-01", c1.code() == 201 && c1.entetes().firstValue("Location").orElse("").contains(dossier) && parent,
                "POST /noeuds/{id}/dossiers : 201, Location, dossier créé sous le parent [5.3.1, T-042, P-06]",
                "HTTP " + c1.code() + ", Location " + c1.entetes().firstValue("Location").orElse("—") + ", sous le parent " + parent);
        verif("C-02", c1b.code() == 201 && dossier.equals(c1b.json().path("id").asText())
                        && c1b.entetes().firstValue("Idempotency-Replayed").isPresent()
                        && c1c.code() == 422 && "IDEMPOTENCE_CONFLIT".equals(c1c.codeMetier())
                        && c1d.code() == 400 && "IDEMPOTENCE_CLE_ABSENTE".equals(c1d.codeMetier()),
                "Création de dossier : rejeu sans doublon, contenu différent 422, clé absente 400 [5.3.2]",
                "rejeu " + c1b.code() + " même id " + dossier.equals(c1b.json().path("id").asText()) + " ; conflit " + c1c.code() + " " + c1c.codeMetier()
                        + " ; sans clé " + c1d.code() + " " + c1d.codeMetier());
        Rep c3 = g.json("POST", "/api/v1/noeuds/" + A + "/dossiers", tSans, Map.of("nom", "qacontrat-" + m + "-intrus"));
        Rep c3b = g.json("POST", "/api/v1/noeuds/" + absent + "/dossiers", tSans, Map.of("nom", "qacontrat-" + m + "-intrus"));
        verif("C-03", c3.code() == 404 && c3b.code() == 404 && masquer(c3.corps()).equals(masquer(c3b.corps())),
                "Création de dossier sous un parent hors périmètre : 404 indiscernable d'un parent absent [5.3.2, P5]",
                "HTTP " + c3.code() + " / absent " + c3b.code());
        Rep c4 = g.json("POST", "/api/v1/noeuds/" + A + "/dossiers", tAdmin, Map.of("nom", ""));
        verif("C-04", c4.code() == 400 && c4.corps().contains("nom"), "Création de dossier invalide : 400 avec l'erreur du champ [5.3.2]",
                "HTTP " + c4.code() + " " + c4.corps());

        // ============ Dépôt (utilisateur puis application) dans le nouveau dossier
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("name", "qacontrat-" + m + "-doc");
        champs.put("typeDocumentId", typeA);
        Rep dep = g.deposer(tDep, pdf, "contrat.pdf", "application/pdf", champs, null);
        String doc = dep.json().path("id").asText();
        String version = dep.json().path("versions").path(0).path("id").asText();
        String W = dep.json().path("workspace").path("id").asText(); // classement par le type documentaire

        // ============ Téléchargement : GET /documents/{id}/contenu (paramètre version)
        Rep t1 = g.get("/api/v1/documents/" + doc + "/contenu", tDep);
        Rep t2 = g.get("/api/v1/documents/" + doc + "/contenu?version=" + version, tDep);
        verif("C-05", t1.code() == 200 && sha256(t1.octets()).equals(sha256(pdf)) && t2.code() == 200 && sha256(t2.octets()).equals(sha256(pdf)),
                "GET /documents/{id}/contenu, version courante et ?version= : octets identiques au dépôt [5.3.1, T-042]",
                "HTTP " + t1.code() + " / version " + t2.code() + " (" + version + ")");
        Rep t3 = g.get("/api/v1/documents/" + doc + "/contenu", tSans);
        Rep t4 = g.get("/api/v1/documents/" + absent + "/contenu", tSans);
        Rep t5 = g.get("/api/v1/documents/" + doc + "/contenu?version=" + UUID.randomUUID(), tDep);
        verif("C-06", t3.code() == 404 && t4.code() == 404 && masquer(t3.corps()).equals(masquer(t4.corps()))
                        && t3.entetes().firstValue("Content-Disposition").isEmpty() && t5.code() == 404,
                "Téléchargement hors périmètre : 404 indiscernable, sans en-tête de fichier ; version étrangère 404 [5.3.2, P5]",
                "hors périmètre " + t3.code() + ", absent " + t4.code() + ", version inconnue " + t5.code());

        // ============ Recherche : POST /recherches (critères, canal, pagination)
        Rep r1 = g.json("POST", "/api/v1/recherches", tDep, Map.of("noeudId", W));
        boolean trouve = r1.corps().contains(doc);
        Rep r2 = g.json("POST", "/api/v1/recherches", tDep, Map.of("noeudId", W, "canal", "API"));
        Rep r3 = g.json("POST", "/api/v1/recherches", tDep, Map.of("noeudId", W, "canal", "INTERFACE"));
        verif("C-07", r1.code() == 200 && trouve && r2.code() == 200 && !r2.corps().contains(doc) && r3.corps().contains(doc)
                        && "INTERFACE".equals(r3.json().path("resultats").path(0).path("canalDepot").asText()),
                "POST /recherches : critère de nœud et critère canal (T-040) appliqués, canal porté par le résultat [5.3.1, T-044]",
                "trouvé " + trouve + ", canal API exclut " + !r2.corps().contains(doc) + ", canal INTERFACE retrouve " + r3.corps().contains(doc));
        Rep r4 = g.json("POST", "/api/v1/recherches", tAdmin, Map.of());
        Rep r5 = g.json("POST", "/api/v1/recherches", tAdmin, Map.of("taille", 500));
        Rep r6 = g.json("POST", "/api/v1/recherches", tAdmin, Map.of("canal", "FAX"));
        verif("C-08", r4.code() == 200 && r4.json().path("taille").asInt() == 50 && r5.json().path("taille").asInt() == 200
                        && r5.json().path("resultats").size() <= 200 && r6.code() == 400,
                "Pagination : taille 50 par défaut, plafonnée à 200 ; canal inconnu 400 [5.3.2]",
                "défaut " + r4.json().path("taille") + ", demandé 500 → " + r5.json().path("taille") + ", canal FAX " + r6.code());
        Rep r7 = g.json("POST", "/api/v1/recherches", tSans, Map.of("noeudId", W));
        Rep r8 = g.json("POST", "/api/v1/recherches", tSans, Map.of("noeudId", absent));
        verif("C-09", !r7.corps().contains(doc) && r7.code() == r8.code() && masquer(r7.corps()).equals(masquer(r8.corps())),
                "Recherche hors périmètre : rien révélé, réponse identique à un nœud absent (filtrage à la source) [5.3.1, P5]",
                "HTTP " + r7.code() + " / absent " + r8.code() + " : " + r7.corps());

        // ============ Consultation des droits : GET /documents/{id}/droits, /noeuds/{id}/droits
        Rep d1 = g.get("/api/v1/documents/" + doc + "/droits", tDep);
        Rep d2 = g.get("/api/v1/noeuds/" + dossier + "/droits", tDep);
        verif("C-10", d1.code() == 200 && d1.json().path("permissions").toString().contains("CONSULTER")
                        && d2.code() == 200 && d2.json().path("permissions").toString().contains("DEPOSER"),
                "GET /documents/{id}/droits et /noeuds/{id}/droits : droits effectifs de l'appelant [5.3.1, T-044]",
                "document " + d1.code() + " " + d1.json().path("permissions") + " ; nœud " + d2.code() + " " + d2.json().path("permissions"));
        Rep d3 = g.get("/api/v1/documents/" + doc + "/droits?pourUtilisateur=" + cSans, tTiers);
        Rep d4 = g.get("/api/v1/documents/" + doc + "/droits?pourUtilisateur=" + cSans, tAdmin);
        verif("C-11", d3.code() == 403 && d4.code() == 200 && !d4.json().path("permissions").toString().contains("CONSULTER"),
                "Droits d'un tiers : permission d'administration exigée (403 sinon) ; l'administrateur lit des droits exacts [5.3.1]",
                "sans administration " + d3.code() + " ; administrateur " + d4.code() + " " + d4.json().path("permissions"));
        Rep d5 = g.get("/api/v1/noeuds/" + dossier + "/droits", tSans);
        Rep d6 = g.get("/api/v1/noeuds/" + absent + "/droits", tSans);
        Rep d7 = g.get("/api/v1/documents/" + doc + "/droits", tSans);
        verif("C-12", d5.code() == 404 && d6.code() == 404 && d7.code() == 404 && masquer(d5.corps()).equals(masquer(d6.corps())),
                "Droits sur un objet hors périmètre : 404 indiscernable [5.3.2, P5]",
                "nœud " + d5.code() + ", absent " + d6.code() + ", document " + d7.code());

        // ============ Dépôt par application, pour le compte d'un utilisateur, puis recherche par canal API
        String idApp = null;
        for (JsonNode a : g.get("/api/v1/applications", tAdmin).json())
            if (a.path("code").asText().equals("qa-contrat")) idApp = a.path("id").asText();
        if (idApp == null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("code", "qa-contrat");
            a.put("nom", "Recette qa contrat");
            a.put("adressesAutorisees", List.of("127.0.0.1", "::1"));
            a.put("quotaMinute", 600);
            a.put("quotaJour", 100000);
            idApp = g.json("POST", "/api/v1/applications", tAdmin, a).json().path("id").asText();
        }
        Rep k = g.json("POST", "/api/v1/applications/" + idApp + "/cles", tAdmin, Map.of("delegation", true));
        g.json("PUT", "/api/v1/cles-api/" + k.json().path("details").path("id").asText() + "/portee", tAdmin,
                Map.of("portee", List.of(Map.of("noeudId", A, "operations", List.of("DEPOT", "CONSULTATION", "RECHERCHE")))));
        String cleApi = k.json().path("cle").asText();
        Map<String, String> champsApi = new LinkedHashMap<>(champs);
        champsApi.put("name", "qacontrat-" + m + "-api");
        Rep a1 = g.deposer(Map.of("X-API-Key", cleApi, "Idempotency-Key", UUID.randomUUID().toString()), pdf, "api.pdf", "application/pdf", champsApi);
        champsApi.put("name", "qacontrat-" + m + "-deleg");
        Rep a2 = g.deposer(Map.of("X-API-Key", cleApi, "Idempotency-Key", UUID.randomUUID().toString(), "X-On-Behalf-Of", env("GED_E3_DEPOSANT", "kelfassi")),
                pdf, "deleg.pdf", "application/pdf", champsApi);
        verif("C-13", (a1.code() == 201 || a1.code() == 202) && (a2.code() == 201 || a2.code() == 202),
                "POST /documents par une application (clé d'API) et pour le compte d'un utilisateur (X-On-Behalf-Of) [5.3.1, T-044]",
                "application " + a1.code() + ", délégué " + a2.code() + " (202 : OCR en attente)" + (a2.code() / 100 == 2 ? "" : " " + a2.corps()));
        Rep a3 = g.appel("POST", "/api/v1/recherches", Map.of("X-API-Key", cleApi), "application/json",
                JSON.writeValueAsBytes(Map.of("noeudId", W, "canal", "API")));
        String idA1 = a1.json().path("id").asText();
        verif("C-14", a3.code() == 200 && a3.corps().contains(idA1) && !a3.corps().contains(doc),
                "POST /recherches par clé d'API, critère canal API : le dépôt de l'application seul [5.3.1, T-040]",
                "HTTP " + a3.code() + ", dépôt API trouvé " + a3.corps().contains(idA1) + ", dépôt interface exclu " + !a3.corps().contains(doc));

        // ============ Spécification OpenAPI : les chemins du contrat y figurent
        Rep o = g.get("/v3/api-docs", tAdmin);
        List<String> chemins = List.of("/api/v1/noeuds/{id}/dossiers", "/api/v1/recherches", "/api/v1/documents/{id}/contenu",
                "/api/v1/documents/{id}/droits", "/api/v1/noeuds/{id}/droits", "/api/v1/documents");
        List<String> manquants = chemins.stream().filter(c -> !o.json().path("paths").has(c)).toList();
        verif("C-15", o.code() == 200 && manquants.isEmpty(), "Spécification OpenAPI 3 : les chemins du contrat §5.3.1 y sont décrits [5.3, P-06]",
                "HTTP " + o.code() + ", manquants " + manquants);
        System.exit(bilan("Contrat §5.3.1 " + g.url));
    }
}
