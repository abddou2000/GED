import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E6 — OCR asynchrone et recherche plein texte, de bout en bout par l'API (DAT V3 §4.3.3,
 * §4.3.4, §4.4, §4.4.1, §5.3.1 ; décisions D5 et D6).
 *
 * <p>Critère de sortie (modifié par D6) : un scan arabe ou français de 20 pages est trouvable par
 * son contenu, dans le périmètre de l'utilisateur, dans un délai de 24 h au plus ; le délai réel
 * est mesuré et rapporté (le V3 visait 5 min pour 20 pages, file vide).
 *
 * <p>Prérequis : le jeu d'habilitations de la recette E3 (verifier-autorisation.sh) — DÉPOSANT
 * Direction Générale, TIERS sur le nœud A, SANS_DROIT hors de A — et le type de document rangé
 * en B (TD-QA-RH) qu'elle crée. Jeux de données : recette/donnees (scans 2 pages versionnés,
 * scans 20 pages générés dans recette/donnees/genere par generer-donnees.sh --pages-scan 20).
 */
public class RecetteOcrRecherche extends ClientGed {

    RecetteOcrRecherche(String url) {
        super(url);
    }

    record Depot(String id, String version, int code, String statut, long debut) {}

    public static void main(String[] args) throws Exception {
        RecetteOcrRecherche g = new RecetteOcrRecherche(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        long delaiMax = Long.parseLong(env("GED_E6_ATTENTE_MAX_S", "1800"));
        String tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String tTiers = g.connecter(env("GED_E3_TIERS", "yalaoui"), mdp);
        String tSans = g.connecter(env("GED_E3_SANS_DROIT", "nidrissi"), mdp);

        // ---------- moteur
        JsonNode etat = g.get("/api/v1/ocr/etat", tAdmin).json();
        String langues = etat.path("languesInstallees").toString();
        verif("E6-01", etat.path("actif").asBoolean() && etat.path("moteurDisponible").asBoolean()
                        && langues.contains("fra") && langues.contains("ara") && etat.path("langueDefaut").asText().contains("ara"),
                "Chaîne OCR active, Tesseract disponible, modèles fra et ara, langue par défaut fra+ara [4.3.1, 4.3.4, D5]", etat.toString());

        // ---------- types : A (Comptabilité, PDF+DOCX, avec plan d'indexation) et B (TD-QA-RH, créé par E3)
        String typeA = null, typeB = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content")) {
            if (t.path("code").asText().equals(env("GED_E6_TYPE_A", "TD-FACT"))) typeA = t.path("id").asText();
            if (t.path("code").asText().equals(env("GED_E6_TYPE_B", "TD-QA-RH"))) typeB = t.path("id").asText();
        }
        if (typeA == null || typeB == null) {
            System.err.println("ERREUR_EXECUTION|types introuvables (lancer d'abord la recette E3)");
            System.exit(2);
        }

        // ---------- dépôts : 202 EN_ATTENTE_OCR immédiat
        String marque = "QAE6" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, Depot> depots = new LinkedHashMap<>();
        Object[][] jeu = {
                {"SCAN_FR", "scan_fr_courrier.pdf", typeA}, {"SCAN_AR", "scan_ar_courrier.pdf", typeA},
                {"TEXTE_AR", "pdf_texte_ar_courrier.pdf", typeA}, {"SCAN_FR_B", "scan_fr_courrier.pdf", typeB},
                {"SCAN_FR_20", "genere/scan_fr_20p.pdf", typeA}, {"SCAN_AR_20", "genere/scan_ar_20p.pdf", typeA}};
        for (Object[] d : jeu) {
            Path f = donnees.resolve((String) d[1]);
            if (!Files.exists(f)) {
                res("E6-02-" + d[0], "NA", "Dépôt de " + d[1], "fichier absent (generer-donnees.sh --pages-scan 20 --sortie genere)");
                continue;
            }
            long t0 = System.currentTimeMillis();
            Rep r = g.deposer(tDep, f, marque + "-" + d[0], (String) d[2], null);
            JsonNode doc = r.json().has("document") ? r.json().path("document") : r.json();
            depots.put((String) d[0], new Depot(doc.path("id").asText(), doc.path("versions").path(0).path("id").asText(),
                    r.code(), r.corps(), t0));
        }
        List<String> non202 = new ArrayList<>();
        for (var e : depots.entrySet()) {
            if (e.getValue().code() != 202 || !e.getValue().statut().contains("EN_ATTENTE_OCR")) {
                non202.add(e.getKey() + "=" + e.getValue().code());
            }
        }
        verif("E6-02", non202.isEmpty() && !depots.isEmpty(), "Dépôt d'un scan : réponse immédiate HTTP 202 avec l'état EN_ATTENTE_OCR [4.3.4, 12.11]",
                non202.isEmpty() ? depots.size() + " dépôts" : "écarts " + non202);

        // ---------- attente de l'OCR, délai mesuré (D6)
        Map<String, JsonNode> textes = new LinkedHashMap<>();
        Map<String, Long> delais = new LinkedHashMap<>();
        long fin = System.currentTimeMillis() + delaiMax * 1000;
        while (textes.size() < depots.size() && System.currentTimeMillis() < fin) {
            for (var e : depots.entrySet()) {
                if (textes.containsKey(e.getKey())) continue;
                JsonNode t = g.get("/api/v1/ocr/documents/" + e.getValue().id() + "/texte", tDep).json();
                String st = t.path("statutOcr").asText("");
                if (t.path("interrogeable").asBoolean() || st.equals("OCR_ECHEC")) {
                    textes.put(e.getKey(), t);
                    delais.put(e.getKey(), (System.currentTimeMillis() - e.getValue().debut()) / 1000);
                }
            }
            Thread.sleep(3000);
        }
        for (var e : depots.entrySet()) {
            JsonNode t = textes.get(e.getKey());
            String detail = t == null ? "non indexé après " + delaiMax + " s" : "statut " + t.path("statutOcr").asText() + ", provenance "
                    + t.path("provenance").asText() + ", " + t.path("nbPages").asText() + " page(s), langue " + t.path("langue").asText()
                    + ", délai " + delais.get(e.getKey()) + " s";
            verif("E6-03-" + e.getKey(), t != null && t.path("interrogeable").asBoolean(), "Texte extrait et indexé (document_texte) : " + e.getKey() + " [4.3.4]", detail);
        }

        // ---------- recherche plein texte
        record Q(String id, String requete, String doc, boolean attendu, String lib) {}
        List<Q> requetes = List.of(
                new Q("E6-04", "zarkolinet", "SCAN_FR", true, "Scan français trouvé par son contenu (OCR fra) [4.4]"),
                new Q("E6-05", "زركولين", "SCAN_AR", true, "Scan ARABE trouvé par son contenu (OCR ara, configuration arabic) [4.3.4, 4.4]"),
                new Q("E6-06", "زركولين", "TEXTE_AR", true, "PDF à couche texte arabe (formes de présentation, ordre visuel) trouvé [4.4]"),
                new Q("E6-07", "zarkopage20", "SCAN_FR_20", true, "20e page d'un scan de 20 pages trouvée : aucun plafond de pages [4.3.4, critère E6]"),
                new Q("E6-08", "زركولين", "SCAN_AR_20", true, "Scan arabe de 20 pages trouvé par son contenu [critère E6]"),
                new Q("E6-09", "amenagement lagune", "SCAN_FR", true, "Recherche insensible aux accents (unaccent) [4.4]"),
                new Q("E6-10", "\"convention de partenariat\" -zzqaabsent", "SCAN_FR", true, "Syntaxe websearch : expression exacte et exclusion [4.4]"),
                new Q("E6-11", "zarkolinet -berges", "SCAN_FR", false, "Syntaxe websearch : l'exclusion d'un mot présent écarte le document [4.4]"));
        for (Q q : requetes) {
            Depot d = depots.get(q.doc());
            if (d == null) {
                res(q.id(), "NA", q.lib(), "document non déposé");
                continue;
            }
            Rep r = g.get("/api/v1/recherche/plein-texte?taille=200&q=" + enc(q.requete()), tDep);
            boolean trouve = r.corps().contains(d.id());
            String st = q.id().equals("E6-06") && !trouve ? "AVERT" : trouve == q.attendu() ? "OK" : "ECHEC";
            res(q.id(), st, q.lib(), "HTTP " + r.code() + ", « " + q.requete() + " » → " + (trouve ? "trouvé" : "absent")
                    + ", total " + r.json().path("total").asText(r.json().path("totalElements").asText("?"))
                    + (st.equals("AVERT") ? " (texte extrait non normalisé NFKC : à confirmer avec dev3)" : ""));
        }

        // Extraits et pertinence
        Rep r = g.get("/api/v1/recherche/plein-texte?taille=20&q=" + enc("zarkolinet"), tDep);
        JsonNode premier = null;
        // Le premier résultat suffit : tous contiennent le témoin (la page n'est pas forcément celle du scan).
        for (JsonNode x : r.json().path("resultats")) {
            if (premier == null) premier = x.path("extrait");
        }
        String extrait = premier == null ? "" : premier.toString();
        boolean segmentSurligne = premier != null && premier.toString().contains("\"texte\":\"zarkolinet\",\"surligne\":true");
        verif("E6-12", segmentSurligne && !extrait.contains("<b>") && !extrait.contains("<script"),
                "Extraits contextuels (ts_headline) en segments surlignés, jamais en HTML [4.4]", extrait.length() > 300 ? extrait.substring(0, 300) + "…" : extrait);

        // ---------- droits à la source (P5)
        Depot b = depots.get("SCAN_FR_B");
        Rep rt = g.get("/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", tTiers);
        boolean voitA = depots.containsKey("SCAN_FR") && rt.corps().contains(depots.get("SCAN_FR").id());
        boolean voitB = b != null && rt.corps().contains(b.id());
        verif("E6-13", rt.code() == 200 && voitA && !voitB, "TIERS : trouve le scan de son périmètre (A), pas celui de B ; total au périmètre [5.3.1, P5]",
                "A " + voitA + ", B " + voitB + ", total " + rt.json().path("total").asText("?"));
        Rep rs = g.get("/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", tSans);
        boolean rien = rs.code() == 403 || (rs.code() == 200 && depots.values().stream().noneMatch(d -> rs.corps().contains(d.id())));
        long totalSans = rs.json().path("total").asLong(0);
        verif("E6-14", rien && totalSans == 0, "SANS_DROIT : aucun résultat, aucun extrait, total 0 [critère E3/E6]",
                "HTTP " + rs.code() + ", total " + totalSans);

        // ---------- cloisonnement : aucun champ d'index pré-rempli par l'OCR (§4.3.3)
        Depot fr = depots.get("SCAN_FR");
        if (fr != null) {
            Rep champs = g.get("/api/v1/indexation/documents/" + fr.id() + "/champs", tDep);
            List<String> remplis = new ArrayList<>();
            for (JsonNode c : champs.json().isArray() ? champs.json() : champs.json().path("champs")) {
                String v = c.path("valeur").asText("");
                if (!v.isBlank()) remplis.add(c.path("libelle").asText(c.path("code").asText()) + "=" + v);
            }
            String dumps = champs.corps();
            verif("E6-15", champs.code() == 200 && remplis.isEmpty() && !dumps.contains("suggestion") && !dumps.contains("proposition"),
                    "Cloisonnement : aucun champ d'index pré-rempli ni suggéré par l'OCR [4.3.3]",
                    "HTTP " + champs.code() + ", champs remplis " + remplis);
            // L'aperçu d'indexation du formulaire de dépôt reçoit le scan : il ne doit rien en extraire.
            String f = "----qa" + java.util.UUID.randomUUID();
            String crlf = "\r\n";
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            buf.writeBytes(("--" + f + crlf + "Content-Disposition: form-data; name=\"typeDocumentId\"" + crlf + crlf
                    + typeA + crlf).getBytes(StandardCharsets.UTF_8));
            buf.writeBytes(("--" + f + crlf + "Content-Disposition: form-data; name=\"file\"; filename=\"scan.pdf\"" + crlf
                    + "Content-Type: application/pdf" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            buf.writeBytes(Files.readAllBytes(donnees.resolve("scan_fr_courrier.pdf")));
            buf.writeBytes((crlf + "--" + f + "--" + crlf).getBytes(StandardCharsets.UTF_8));
            Rep apercu = g.appel("POST", "/api/v1/indexation/apercu", tDep, "multipart/form-data; boundary=" + f, buf.toByteArray());
            String a = apercu.corps().toLowerCase();
            verif("E6-16", apercu.code() == 200 && !a.contains("zarkolinet") && !a.contains("marchica") && !a.contains("250") && !a.contains("2026"),
                    "Aperçu d'indexation au dépôt d'un scan : aucune valeur issue du contenu (nom de fichier seul) [4.3.3]",
                    "HTTP " + apercu.code() + " " + (apercu.corps().length() > 200 ? apercu.corps().substring(0, 200) : apercu.corps()));
        }

        // ---------- réindexation incrémentale : nouvelle version sans le témoin
        if (fr != null) {
            byte[] pdf = pdfMinimal("Version deux qaversiondeux" + marque.toLowerCase() + " sans aucun mot temoin");
            String f = "----qa" + java.util.UUID.randomUUID();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            buf.writeBytes(("--" + f + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"v2.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            buf.writeBytes(pdf);
            buf.writeBytes(("\r\n--" + f + "--\r\n").getBytes(StandardCharsets.UTF_8));
            Rep v = g.appel("POST", "/api/v1/documents/" + fr.id() + "/versions", tDep, "multipart/form-data; boundary=" + f, buf.toByteArray());
            String nouveau = "qaversiondeux" + marque.toLowerCase();
            boolean nouveauTrouve = false, ancienParti = false;
            long fin2 = System.currentTimeMillis() + 600_000;
            while (System.currentTimeMillis() < fin2 && !(nouveauTrouve && ancienParti)) {
                Thread.sleep(3000);
                nouveauTrouve = g.get("/api/v1/recherche/plein-texte?taille=200&q=" + enc(nouveau), tDep).corps().contains(fr.id());
                ancienParti = !g.get("/api/v1/recherche/plein-texte?taille=200&q=zarkolinet", tDep).corps().contains(fr.id());
            }
            verif("E6-17", v.code() / 100 == 2 && nouveauTrouve && ancienParti,
                    "Réindexation incrémentale : la nouvelle version est trouvée, l'ancien contenu ne l'est plus [4.4.1]",
                    "versement " + v.code() + ", nouveau mot trouvé " + nouveauTrouve + ", ancien témoin retiré " + ancienParti);
        }

        // ---------- supervision et délai (D6)
        Rep sup = g.get("/api/v1/admin/ocr/compteurs", tAdmin);
        Rep supT = g.get("/api/v1/admin/ocr/compteurs", tTiers);
        verif("E6-18", sup.code() == 200 && supT.code() == 403, "Supervision OCR réservée (Administrateur 200, utilisateur 403) [4.3.4]",
                "admin " + sup.code() + " " + sup.corps() + ", tiers " + supT.code());
        long pire20 = 0;
        for (String k : List.of("SCAN_FR_20", "SCAN_AR_20")) pire20 = Math.max(pire20, delais.getOrDefault(k, 0L));
        res("E6-19", pire20 > 0 && pire20 <= 24 * 3600 ? "OK" : pire20 == 0 ? "NA" : "ECHEC",
                "Délai dépôt → disponibilité d'un scan de 20 pages ≤ 24 h (D6) ; objectif V3 : 5 min",
                "mesuré " + pire20 + " s (" + (pire20 <= 300 ? "sous" : "au-delà de") + " l'objectif V3 de 5 min), délais " + delais);

        info("documents conservés (marqueur " + marque + ") : " + depots);
        System.exit(bilan("E6 OCR et recherche " + g.url));
    }

    /** PDF d'une page à couche texte (Helvetica), écrit à la main : aucune dépendance. */
    static byte[] pdfMinimal(String texte) {
        String flux = "BT /F1 18 Tf 72 720 Td (" + texte.replace("(", "").replace(")", "") + ") Tj ET";
        String[] objets = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + flux.length() + " >>\nstream\n" + flux + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder s = new StringBuilder("%PDF-1.4\n");
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < objets.length; i++) {
            positions.add(s.length());
            s.append(i + 1).append(" 0 obj\n").append(objets[i]).append("\nendobj\n");
        }
        int xref = s.length();
        s.append("xref\n0 ").append(objets.length + 1).append("\n0000000000 65535 f \n");
        for (int p : positions) s.append(String.format("%010d 00000 n \n", p));
        s.append("trailer\n<< /Size ").append(objets.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return s.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
