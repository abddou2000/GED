import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette de la réception documentaire (DAT V3 §5.1, dossier fonctionnel 4.1.3, T-040) : chaque
 * dépôt est enregistré, horodaté, et porte sa SOURCE (canal) et son DÉPOSANT sur le document
 * lui-même, quel que soit le chemin : interface, application par clé d'API, bureau d'ordre,
 * dépôt pour le compte d'un utilisateur.
 *
 * <p>Le canal BUREAU_ORDRE suppose que l'instance déclare l'application de recette dans
 * {@code ged.depot.applications-bureau-ordre} (code GED_E9_APPLICATION_BO, défaut
 * « qa-bureau-ordre »). Prérequis : jeu d'habilitations de la recette E3.
 */
public class RecetteReception extends ClientGed {

    RecetteReception(String url) {
        super(url);
    }

    static RecetteReception g;
    static String tAdmin;

    /** Application (réutilisée si son code existe déjà) et nouvelle clé ; portée DEPOT+CONSULTATION sur le nœud. */
    static String[] cle(String code, boolean delegation, String noeud) throws Exception {
        String idApp = null;
        for (JsonNode a : g.get("/api/v1/applications", tAdmin).json())
            if (a.path("code").asText().equals(code)) idApp = a.path("id").asText();
        if (idApp == null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("code", code);
            a.put("nom", "Recette qa " + code);
            a.put("adressesAutorisees", List.of("127.0.0.1", "::1"));
            a.put("quotaMinute", 600);
            a.put("quotaJour", 100000);
            idApp = g.json("POST", "/api/v1/applications", tAdmin, a).json().path("id").asText();
        }
        Rep k = g.json("POST", "/api/v1/applications/" + idApp + "/cles", tAdmin, Map.of("delegation", delegation));
        String idCle = k.json().path("details").path("id").asText();
        g.json("PUT", "/api/v1/cles-api/" + idCle + "/portee", tAdmin,
                Map.of("portee", List.of(Map.of("noeudId", noeud, "operations", List.of("DEPOT", "CONSULTATION")))));
        return new String[]{idApp, k.json().path("cle").asText()};
    }

    static String controle(JsonNode d, String canal, String application, String deposant, boolean delegue, Instant avant) {
        StringBuilder e = new StringBuilder();
        if (!canal.equals(d.path("canalDepot").asText())) e.append("canal ").append(d.path("canalDepot").asText()).append(" ; ");
        String app = d.path("applicationId").isNull() ? null : d.path("applicationId").asText(null);
        if (application == null ? app != null : !application.equals(app)) e.append("application ").append(app).append(" ; ");
        if (deposant != null && !deposant.equals(d.path("deposantUtilisateurId").asText())) e.append("déposant ").append(d.path("deposantUtilisateurId").asText()).append(" ; ");
        if (delegue != d.path("depotDelegue").asBoolean()) e.append("délégué ").append(d.path("depotDelegue").asBoolean()).append(" ; ");
        Instant cree = d.path("createdAt").isMissingNode() ? null : Instant.parse(d.path("createdAt").asText());
        if (cree == null || cree.isBefore(avant) || cree.isAfter(Instant.now().plus(Duration.ofSeconds(5)))) e.append("horodatage ").append(cree).append(" ; ");
        return e.toString();
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteReception(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        byte[] pdf = Files.readAllBytes(Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf"));
        tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String cTiers = env("GED_E3_TIERS", "yalaoui");
        String uDep = g.get("/api/v1/auth/me", tDep).json().path("id").asText();
        String uTiers = null;
        for (JsonNode u : g.get("/api/v1/admin/utilisateurs", tAdmin).json())
            if (u.path("identifiant").asText().equalsIgnoreCase(cTiers)) uTiers = u.path("id").asText();
        String A = null, typeA = null;
        for (JsonNode x : g.get("/api/v1/workspaces/tree", tAdmin).json()) if (x.path("name").asText().equals("Comptabilité")) A = x.path("id").asText();
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content"))
            if (t.path("code").asText().equals("TD-FACT")) typeA = t.path("id").asText();
        String m = Long.toString(System.currentTimeMillis(), 36);

        // Interface (jeton utilisateur) ; tentative de forcer le canal par un champ du formulaire
        Instant t0 = Instant.now().minusSeconds(1);
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("name", "qarecep-" + m + "-interface");
        champs.put("typeDocumentId", typeA);
        champs.put("canalDepot", "REPRISE");
        champs.put("source", "BUREAU_ORDRE");
        JsonNode dUi = g.deposer(tDep, pdf, "ui.pdf", "application/pdf", champs, null).json();
        String e1 = controle(dUi, "INTERFACE", null, uDep, false, t0);
        JsonNode fUi = g.get("/api/v1/documents/" + dUi.path("id").asText(), tDep).json();
        verif("R-01", e1.isEmpty() && controle(fUi, "INTERFACE", null, uDep, false, t0).isEmpty(),
                "Dépôt depuis l'interface : canal INTERFACE, déposant, horodatage, portés et relus sur le document ; canal non falsifiable par le formulaire [5.1, 4.1.3, T-040]",
                e1.isEmpty() ? "canal " + dUi.path("canalDepot").asText() + ", déposant " + dUi.path("deposantUtilisateurId").asText() : e1);

        // Application par clé d'API
        String[] api = cle("qa-recep-api", false, A);
        JsonNode dApi = g.deposer(Map.of("X-API-Key", api[1], "Idempotency-Key", UUID.randomUUID().toString()), pdf, "api.pdf",
                "application/pdf", Map.of("name", "qarecep-" + m + "-api", "typeDocumentId", typeA)).json();
        String e2 = controle(dApi, "API", api[0], null, false, t0);
        verif("R-02", e2.isEmpty(), "Dépôt par une application (clé d'API) : canal API, application appelante identifiée [5.1, 5.2, T-040]",
                e2.isEmpty() ? "application " + dApi.path("applicationId").asText() + ", déposant " + dApi.path("deposantUtilisateurId").asText() : e2);

        // Bureau d'ordre (application déclarée comme telle par la configuration)
        String[] bo = cle(env("GED_E9_APPLICATION_BO", "qa-bureau-ordre"), false, A);
        JsonNode dBo = g.deposer(Map.of("X-API-Key", bo[1], "Idempotency-Key", UUID.randomUUID().toString()), pdf, "bo.pdf",
                "application/pdf", Map.of("name", "qarecep-" + m + "-bo", "typeDocumentId", typeA)).json();
        String e3 = controle(dBo, "BUREAU_ORDRE", bo[0], null, false, t0);
        verif("R-03", e3.isEmpty(), "Dépôt par le bureau d'ordre digital : canal BUREAU_ORDRE, application et horodatage [5.1, T-040]",
                e3.isEmpty() ? "canal " + dBo.path("canalDepot").asText() : e3 + " (l'instance déclare-t-elle ged.depot.applications-bureau-ordre ?)");

        // Pour le compte d'un utilisateur
        String[] del = cle("qa-recep-deleg", true, A);
        JsonNode dDel = g.deposer(Map.of("X-API-Key", del[1], "X-On-Behalf-Of", cTiers, "Idempotency-Key", UUID.randomUUID().toString()),
                pdf, "del.pdf", "application/pdf", Map.of("name", "qarecep-" + m + "-delegue", "typeDocumentId", typeA)).json();
        String e4 = controle(dDel, "API", del[0], uTiers, true, t0);
        verif("R-04", e4.isEmpty(), "Dépôt pour le compte d'un utilisateur : application ET déposant délégué portés par le document [5.1, 5.5, T-040]",
                e4.isEmpty() ? "application " + dDel.path("applicationId").asText() + ", déposant " + dDel.path("deposantUtilisateurId").asText() : e4);

        // Cohérence avec le journal d'audit
        JsonNode ev = g.get("/api/v1/audit/evenements?action=DOCUMENT_DEPOSE&objetId=" + dDel.path("id").asText(), tAdmin).json().path("content").path(0);
        verif("R-05", ev.path("acteurUtilisateurId").asText().equals(uTiers) && ev.path("acteurApplicationId").asText().equals(del[0]),
                "Source et déposant du document identiques à ceux du journal d'audit [5.1, 7.4.1]", ev.path("acteurUtilisateurId") + " / " + ev.path("acteurApplicationId"));
        System.exit(bilan("Réception 5.1 / 4.1.3 " + g.url));
    }
}
