import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E4 — journal d'audit fonctionnel et journal technique, de bout en bout par l'API
 * (DAT V3 §7.1, §7.3, §7.4.1, §7.4.3 ; décision D11).
 *
 * <p>Critère de sortie E4 : chaque action produit un événement (l'inaltérabilité est jouée par
 * verifier-journal.sh et verifier-scellement.sh). Chaque action est réalisée par l'API, puis
 * l'événement correspondant est recherché dans le journal par son code et son objet, avec son
 * acteur, son adresse, son résultat et son identifiant de trace, lequel doit se retrouver dans
 * le journal technique (pattern de l'Article 50).
 *
 * <p>Prérequis : jeu d'habilitations de la recette E3, antivirus actif (simulé sur le poste).
 * Variables : GED_URL, GED_RECETTE_MOT_DE_PASSE, comptes GED_E3_*, GED_DONNEES,
 * GED_JOURNAL_TECHNIQUE (fichier du journal applicatif, facultatif).
 */
public class RecetteAudit extends ClientGed {

    RecetteAudit(String url) {
        super(url);
    }

    static String tAdmin;
    static RecetteAudit g;

    /** Événements d'une action sur un objet, depuis un instant. */
    static List<JsonNode> evenements(String action, String objetId, Instant depuis) throws Exception {
        String q = "/api/v1/audit/evenements?taille=200&action=" + action + "&du=" + depuis
                + (objetId != null ? "&objetId=" + objetId : "");
        List<JsonNode> r = new ArrayList<>();
        for (JsonNode e : g.get(q, tAdmin).json().path("content")) r.add(e);
        return r;
    }

    static void attendu(String id, String action, String objetId, Instant depuis, String acteurAttendu, String lib) throws Exception {
        List<JsonNode> ev = List.of();
        for (int i = 0; i < 10 && ev.isEmpty(); i++) {
            ev = evenements(action, objetId, depuis);
            if (ev.isEmpty()) Thread.sleep(500);  // événements publiés après validation de la transaction
        }
        JsonNode e = ev.isEmpty() ? null : ev.get(0);
        boolean acteurOk = e != null && (acteurAttendu == null || acteurAttendu.equals(e.path("acteurUtilisateurId").asText()));
        boolean complet = e != null && !e.path("horodatage").isMissingNode() && !e.path("adresseIp").asText("").isBlank()
                && !e.path("resultat").asText("").isBlank() && !e.path("traceId").isNull() && !e.path("traceId").asText("").isBlank();
        verif(id, e != null && acteurOk && complet, lib + " — événement " + action + " [7.4.1]",
                e == null ? "aucun événement" : "acteur " + e.path("acteurUtilisateurId").asText() + " (" + e.path("acteurNom").asText()
                        + "), IP " + e.path("adresseIp").asText() + ", résultat " + e.path("resultat").asText()
                        + ", trace " + e.path("traceId").asText() + (acteurOk ? "" : " — acteur attendu " + acteurAttendu));
        if (e != null) traces.add(e.path("traceId").asText().replace("-", ""));
    }

    static final List<String> traces = new ArrayList<>();
    /** Trace d'une requête qui écrit aussi au journal technique (refus antivirus journalisé en WARN). */
    static String traceInfecte = "";

    public static void main(String[] args) throws Exception {
        g = new RecetteAudit(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        Path pdf = donnees.resolve("pdf_texte_fr_convention.pdf");
        String cAdmin = env("GED_E3_ADMIN", "sbennani"), cDep = env("GED_E3_DEPOSANT", "kelfassi");
        String cTiers = env("GED_E3_TIERS", "yalaoui"), cSans = env("GED_E3_SANS_DROIT", "nidrissi");
        Instant t0 = Instant.now().minusSeconds(2);
        String marque = "QAE4" + Long.toString(System.currentTimeMillis(), 36);

        // Connexion refusée puis réussie
        g.json("POST", "/api/v1/auth/login", null, Map.of("identifiant", "qaintrus", "motDePasse", "mauvais"));
        tAdmin = g.connecter(cAdmin, mdp);
        String tDep = g.connecter(cDep, mdp), tTiers = g.connecter(cTiers, mdp), tSans = g.connecter(cSans, mdp);
        String uDep = g.get("/api/v1/auth/me", tDep).json().path("id").asText();
        String uTiers = g.get("/api/v1/auth/me", tTiers).json().path("id").asText();
        String uAdmin = g.get("/api/v1/auth/me", tAdmin).json().path("id").asText();
        List<JsonNode> refus = evenements("CONNEXION_REFUSEE", null, t0);
        boolean refusTrace = refus.stream().anyMatch(e -> e.toString().contains("qaintrus") && e.path("resultat").asText().contains("REFUS"));
        verif("E4-A01", refusTrace, "Connexion refusée tracée avec l'identifiant saisi et le résultat REFUS [7.4.1, 3.4.1]", refus.size() + " événement(s)");
        attendu("E4-A02", "CONNEXION_REUSSIE", uDep, t0, uDep, "Connexion réussie");

        // Types et nœuds du jeu E3
        String typeA = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content"))
            if (t.path("code").asText().equals("TD-FACT")) typeA = t.path("id").asText();
        Map<String, String> noeuds = new LinkedHashMap<>();
        pile(g.get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        String A = noeuds.get("Comptabilité"), C = noeuds.get("Projets");

        // Cycle d'un document
        JsonNode dep = g.deposer(tDep, pdf, marque + "-audit", typeA, null).json();
        String d = dep.path("id").asText(), v = dep.path("versions").path(0).path("id").asText();
        attendu("E4-A03", "DOCUMENT_DEPOSE", d, t0, uDep, "Dépôt");
        g.get("/api/v1/documents/" + d, tTiers);
        attendu("E4-A04", "DOCUMENT_CONSULTE", d, t0, uTiers, "Consultation de la fiche");
        g.get("/api/v1/documents/" + d + "/download", tTiers);
        attendu("E4-A05", "DOCUMENT_TELECHARGE", d, t0, uTiers, "Téléchargement");
        g.get("/api/v1/versions/" + v + "/apercu", tTiers);
        attendu("E4-A06", "APERCU_CONSULTE", null, t0, uTiers, "Aperçu (événement distinct du téléchargement, §6.1.6)");
        g.json("PUT", "/api/v1/documents/" + d, tDep, Map.of("name", marque + "-audit-renomme"));
        List<JsonNode> modifs = new ArrayList<>(evenements("METADONNEES_MODIFIEES", d, t0));
        modifs.addAll(evenements("DOCUMENT_RENOMME", d, t0));
        boolean avantApres = modifs.stream().anyMatch(e -> e.toString().contains(marque + "-audit\"") && e.toString().contains("renomme"));
        verif("E4-A07", avantApres, "Modification : valeurs avant et après au journal [7.4.1]", modifs.isEmpty() ? "aucun événement" : modifs.get(0).path("avant") + " → " + modifs.get(0).path("apres"));
        g.json("POST", "/api/v1/documents/" + d + "/rattachements", tDep, Map.of("noeudId", C));
        attendu("E4-A08", "RATTACHEMENT_AJOUTE", d, t0, uDep, "Ajout de rattachement");
        g.appel("DELETE", "/api/v1/documents/" + d + "/rattachements/" + C, tDep, null, null);
        attendu("E4-A09", "RATTACHEMENT_RETIRE", d, t0, uDep, "Retrait de rattachement");
        g.appel("PATCH", "/api/v1/documents/" + d + "/verrou?verrouille=true", tAdmin, null, null);
        attendu("E4-A10", "DOCUMENT_VERROUILLE", d, t0, uAdmin, "Verrouillage");
        g.appel("PATCH", "/api/v1/documents/" + d + "/verrou?verrouille=false", tAdmin, null, null);
        g.appel("POST", "/api/v1/documents/" + d + "/archivage", tDep, null, null);
        attendu("E4-A11", "DOCUMENT_ARCHIVE", d, t0, uDep, "Archivage");
        g.appel("DELETE", "/api/v1/documents/" + d + "/archivage", tAdmin, null, null);
        attendu("E4-A12", "DOCUMENT_DESARCHIVE", d, t0, uAdmin, "Désarchivage");
        g.appel("POST", "/api/v1/exports/dossiers/" + A, tTiers, null, null);
        attendu("E4-A13", "DOCUMENT_EXPORTE", d, t0, uTiers, "Export ZIP (un événement par document exporté)");
        g.appel("DELETE", "/api/v1/documents/" + d, tDep, null, null);
        attendu("E4-A14", "DOCUMENT_SUPPRIME", d, t0, uDep, "Suppression (corbeille)");
        g.appel("PATCH", "/api/v1/documents/" + d + "/restore", tDep, null, null);
        attendu("E4-A15", "DOCUMENT_RESTAURE", d, t0, uDep, "Restauration depuis la corbeille");
        g.appel("DELETE", "/api/v1/documents/" + d, tDep, null, null);
        g.appel("POST", "/api/v1/documents/" + d + "/purge", tAdmin, null, null);
        attendu("E4-A16", "DOCUMENT_PURGE", d, t0, uAdmin, "Purge (l'audit survit au document)");

        // Refus de droit et fichier infecté
        String inconnu = java.util.UUID.randomUUID().toString();
        g.get("/api/v1/documents/" + inconnu, tSans);
        JsonNode dB = g.deposer(tDep, pdf, marque + "-hors-perimetre", typeA, "CONFIDENTIEL").json();
        g.get("/api/v1/documents/" + dB.path("id").asText(), tSans);
        // 403 : objet visible sans la permission (le TIERS n'a pas Supprimer) ; 404 : objet hors périmètre.
        g.get("/api/v1/audit/evenements?taille=1", tTiers);
        List<JsonNode> refus403 = evenements("ACCES_REFUSE", null, t0);
        verif("E4-A17", refus403.stream().anyMatch(e -> e.path("resultat").asText().contains("REFUS")),
                "Refus de droit en 403 tracé — ACCES_REFUSE avec la route refusée [7.4.1]", refus403.size() + " événement(s)");
        String idHors = dB.path("id").asText();
        // Code retenu par le correctif d'ANO-E4-002 : ACCES_HORS_PERIMETRE (réponse 404 inchangée pour le client).
        List<JsonNode> hors = evenements("ACCES_HORS_PERIMETRE", idHors, t0);
        boolean trace404 = !hors.isEmpty() && hors.get(0).path("resultat").asText().contains("REFUS");
        boolean inconnuTrace = !evenements("ACCES_HORS_PERIMETRE", inconnu, t0).isEmpty();
        res("E4-A17b", trace404 && !inconnuTrace ? "OK" : "ECHEC",
                "Accès à un objet hors périmètre (réponse 404) tracé ACCES_HORS_PERIMETRE ; identifiant inexistant non tracé [7.4.1 « les refus de droits sont tracés », ANO-E4-002]",
                trace404 ? (inconnuTrace ? "identifiant inexistant tracé" : "acteur " + hors.get(0).path("acteurNom").asText())
                        : "aucun ACCES_HORS_PERIMETRE pour GET d'un document confidentiel hors périmètre");
        byte[] eicar = (new StringBuilder("-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X").reverse()
                + new StringBuilder("*H+H$!ELIF-TSET-SURIVITNA").reverse().toString()).getBytes();
        String typeTxt = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content"))
            if (t.path("typeAutorise").toString().contains("txt")) typeTxt = t.path("id").asText();
        if (typeTxt != null) {
            Rep inf = g.deposer(tDep, eicar, "facture.txt", "text/plain", Map.of("name", marque + "-eicar", "typeDocumentId", typeTxt), null);
            List<JsonNode> ev = evenements("FICHIER_INFECTE", null, t0);
            if (!ev.isEmpty()) traceInfecte = ev.get(0).path("traceId").asText().replace("-", "");
            verif("E4-A18", inf.code() == 422 && !ev.isEmpty(), "Fichier infecté refusé et tracé — FICHIER_INFECTE [6.1.5, 7.4.1] (antivirus simulé)",
                    "HTTP " + inf.code() + ", " + ev.size() + " événement(s)");
        } else {
            res("E4-A18", "NA", "Fichier infecté tracé", "aucun type acceptant le texte");
        }

        // Habilitation : attribution et retrait, avant/après
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("sujetType", "UTILISATEUR");
        h.put("sujetId", uTiers);
        h.put("roleId", "0192a000-0000-7000-8000-000000000004");
        h.put("noeudId", C);
        h.put("ruptureHeritage", false);
        Rep hab = g.json("POST", "/api/v1/admin/habilitations", tAdmin, h);
        String idHab = hab.json().path("id").asText();
        g.appel("DELETE", "/api/v1/admin/habilitations/" + idHab, tAdmin, null, null);
        List<JsonNode> habs = evenements("HABILITATION_MODIFIEE", null, t0);
        boolean ajout = habs.stream().anyMatch(e -> e.path("avant").isNull() || e.path("avant").isMissingNode() ? e.path("apres").toString().contains(uTiers) : false);
        boolean retrait = habs.stream().anyMatch(e -> e.path("avant").toString().contains(uTiers) && (e.path("apres").isNull() || e.path("apres").isMissingNode()));
        verif("E4-A19", hab.code() == 201 && ajout && retrait && habs.stream().allMatch(e -> uAdmin.equals(e.path("acteurUtilisateurId").asText())),
                "Modification de droits tracée avec avant et après (attribution et retrait) [12.2.3, 7.4.1]", habs.size() + " événement(s), ajout " + ajout + ", retrait " + retrait);

        // Déconnexion
        g.appel("POST", "/api/v1/auth/logout", tSans, null, null);
        attendu("E4-A20", "DECONNEXION", null, t0, null, "Déconnexion");

        // ---------- consultation et export (§7.4.3, D11)
        Rep lecTiers = g.get("/api/v1/audit/evenements?taille=1", tTiers);
        Rep lecDg = g.get("/api/v1/audit/evenements?taille=1", tDep);
        verif("E4-C01", lecTiers.code() == 403 && lecDg.code() == 200,
                "Consultation du journal réservée à l'Administrateur et à la Direction Générale [7.4.3]", "Utilisateur standard " + lecTiers.code() + ", DG " + lecDg.code());
        attendu("E4-C02", "AUDIT_CONSULTE", null, t0, uDep, "La consultation du journal est elle-même auditée");
        for (String fmt : List.of("csv", "json")) {
            Rep ex = g.get("/api/v1/audit/export?format=" + fmt + "&du=" + t0, tAdmin);
            String annonce = ex.entetes().firstValue("X-Empreinte-SHA256").orElse("");
            boolean contenu = new String(ex.octets(), java.nio.charset.StandardCharsets.UTF_8).contains("DOCUMENT_DEPOSE");
            verif("E4-C03-" + fmt, ex.code() == 200 && annonce.equalsIgnoreCase(sha256(ex.octets())) && contenu,
                    "Export " + fmt.toUpperCase() + " de la sélection avec empreinte SHA-256 exacte [7.4.3]", "HTTP " + ex.code() + ", empreinte annoncée " + annonce);
        }
        attendu("E4-C04", "AUDIT_EXPORTE", null, t0, uAdmin, "L'export du journal est audité");
        long idLigne = g.get("/api/v1/audit/evenements?taille=1", tAdmin).json().path("content").path(0).path("id").asLong();
        List<String> ecritures = new ArrayList<>();
        for (String m : List.of("PUT", "PATCH", "DELETE", "POST")) {
            for (String ch : List.of("/api/v1/audit/evenements/" + idLigne, "/api/v1/audit/evenements")) {
                Rep r = g.appel(m, ch, tAdmin, "application/json", m.equals("DELETE") ? null : "{\"action\":\"QA\"}".getBytes());
                if (r.code() / 100 == 2) ecritures.add(m + " " + ch + " → " + r.code());
            }
        }
        verif("E4-C05", ecritures.isEmpty(), "Aucune route de modification ou de suppression du journal, même pour l'Administrateur [D11, 7.4.2]",
                ecritures.isEmpty() ? "PUT, PATCH, DELETE, POST refusés" : String.join(" ; ", ecritures));

        // ---------- journal technique (§7.1) : pattern et corrélation par traceId
        String fichier = env("GED_JOURNAL_TECHNIQUE", null);
        if (fichier != null && Files.exists(Path.of(fichier))) {
            List<String> lignes = Files.readAllLines(Path.of(fichier));
            Pattern motif = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[[^\\]]*\\] \\[[^\\]]*\\] \\[[^\\]]+\\] - [^ /]*/[^ ]* (TRACE|DEBUG|INFO |WARN |ERROR) - \\S+ - .*");
            long conformes = lignes.stream().filter(l -> Character.isDigit(l.isEmpty() ? 'x' : l.charAt(0))).filter(l -> motif.matcher(l).matches()).count();
            long datees = lignes.stream().filter(l -> l.matches("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*")).count();
            verif("E4-L01", datees > 0 && conformes == datees, "Journal technique au pattern de l'Article 50 (username, ip, thread, traceId/spanId) [7.1]",
                    conformes + "/" + datees + " lignes conformes");
            long authentifiees = lignes.stream().filter(l -> l.contains("[" + cDep + "]")).count();
            // En INFO, seules les requêtes qui journalisent quelque chose laissent une ligne : on
            // corrèle celle du refus antivirus, journalisée en WARN, avec son événement d'audit.
            boolean correlee = !traceInfecte.isBlank() && lignes.stream().anyMatch(l -> l.contains(traceInfecte) && l.contains("[" + cDep + "]"));
            verif("E4-L02", authentifiees > 0 && correlee, "username renseigné pour les requêtes authentifiées ; traceId de l'audit identique à celui du journal technique [7.1, 7.3]",
                    authentifiees + " lignes de " + cDep + ", trace du refus antivirus " + traceInfecte + " retrouvée : " + correlee);
        } else {
            res("E4-L01", "NA", "Journal technique au pattern de l'Article 50", "GED_JOURNAL_TECHNIQUE non fourni");
        }
        System.exit(bilan("E4 audit " + g.url));
    }

    static void pile(JsonNode n, Map<String, String> acc) {
        for (JsonNode x : n) {
            acc.put(x.path("name").asText(), x.path("id").asText());
            pile(x.path("children"), acc);
        }
    }
}
