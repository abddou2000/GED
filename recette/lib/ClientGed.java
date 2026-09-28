import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client HTTP commun aux recettes Java (E6, E7) : connexion avec respect de la limitation de
 * débit, appels JSON, dépôt multipart, téléchargement binaire, sortie RESULTAT|… et BILAN.
 * Compilé avec la recette par son lanceur (javac), jamais embarqué dans l'application.
 * JDK 17 + Jackson du classpath du backend (décision D5 : aucun Python).
 */
public class ClientGed {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    static int ok, echec, avert, na;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public final String url;

    public ClientGed(String url) {
        this.url = url.replaceAll("/+$", "");
    }

    public record Rep(int code, byte[] octets, HttpHeaders entetes) {
        public String corps() {
            return new String(octets, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return octets.length == 0 ? JSON.nullNode() : JSON.readTree(octets);
            } catch (Exception e) {
                return JSON.nullNode();
            }
        }

        public String codeMetier() {
            JsonNode j = json();
            return j.path("code").asText(j.path("erreur").asText(""));
        }
    }

    // ------------------------------------------------------------------ sortie

    public static void res(String id, String statut, String lib, String detail) {
        switch (statut) {
            case "OK" -> ok++;
            case "ECHEC" -> echec++;
            case "AVERT" -> avert++;
            default -> na++;
        }
        OUT.println("RESULTAT|" + id + "|" + statut + "|" + lib + "|"
                + (detail == null ? "" : detail.replace('|', '/').replace('\n', ' ')));
    }

    public static void verif(String id, boolean cond, String lib, String detail) {
        res(id, cond ? "OK" : "ECHEC", lib, detail);
    }

    public static void info(String s) {
        OUT.println("# " + s);
    }

    public static int bilan(String titre) {
        OUT.println("BILAN|" + titre + "|ok=" + ok + "|echec=" + echec + "|avert=" + avert + "|na=" + na);
        return echec == 0 ? 0 : 1;
    }

    public static String env(String nom, String defaut) {
        String v = System.getenv(nom);
        return v == null || v.isBlank() ? defaut : v;
    }

    public static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // ------------------------------------------------------------------ HTTP

    public Rep appel(String methode, String chemin, String jeton, String type, byte[] corps) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        if (jeton != null) h.put("Authorization", "Bearer " + jeton);
        // Idempotency-Key obligatoire sur les créations (§5.3.2) : une clé neuve par appel.
        if (methode.equals("POST")) h.put("Idempotency-Key", UUID.randomUUID().toString());
        return appel(methode, chemin, h, type, corps);
    }

    /** Appel avec en-têtes libres (X-API-Key, Idempotency-Key, X-On-Behalf-Of, X-Forwarded-For…). */
    public Rep appel(String methode, String chemin, Map<String, String> entetes, String type, byte[] corps) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + chemin)).timeout(Duration.ofSeconds(300));
        entetes.forEach(b::header);
        if (type != null) b.header("Content-Type", type);
        b.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corps));
        HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Rep(r.statusCode(), r.body(), r.headers());
    }

    public Rep get(String chemin, String jeton) throws Exception {
        return appel("GET", chemin, jeton, null, null);
    }

    public Rep json(String methode, String chemin, String jeton, Object corps) throws Exception {
        return appel(methode, chemin, jeton, "application/json", corps == null ? null : JSON.writeValueAsBytes(corps));
    }

    /** Connexion ; attend le Retry-After si la limitation de débit (5/min/IP) répond 429. */
    public String connecter(String compte, String mdp) throws Exception {
        for (int essai = 0; essai < 4; essai++) {
            Rep r = json("POST", "/api/v1/auth/login", null, Map.of("identifiant", compte, "motDePasse", mdp));
            if (r.code() == 200) return r.json().path("token").asText();
            if (r.code() != 429) throw new IllegalStateException("connexion de " + compte + " : HTTP " + r.code());
            long attente = r.entetes().firstValueAsLong("Retry-After").orElse(60);
            info("limitation de débit : attente de " + (attente + 1) + " s avant de connecter " + compte);
            Thread.sleep((attente + 1) * 1000);
        }
        throw new IllegalStateException("connexion de " + compte + " impossible (429 persistant)");
    }

    /** Dépôt multipart : champs simples, fichier, et partie JSON « metadonnees » facultative. */
    public Rep deposer(String jeton, byte[] contenu, String nomFichier, String typeContenu, Map<String, String> champs,
                       String metadonneesJson) throws Exception {
        Object[] m = multipart(contenu, nomFichier, typeContenu, champs, metadonneesJson);
        return appel("POST", "/api/v1/documents", jeton, "multipart/form-data; boundary=" + m[0], (byte[]) m[1]);
    }

    /** Dépôt avec en-têtes libres (clé d'API, délégation, clé d'idempotence imposée). */
    public Rep deposer(Map<String, String> entetes, byte[] contenu, String nomFichier, String typeContenu,
                       Map<String, String> champs) throws Exception {
        Object[] m = multipart(contenu, nomFichier, typeContenu, champs, null);
        return appel("POST", "/api/v1/documents", entetes, "multipart/form-data; boundary=" + m[0], (byte[]) m[1]);
    }

    /** Corps multipart d'un dépôt : {frontière, octets}. */
    public static Object[] multipart(byte[] contenu, String nomFichier, String typeContenu, Map<String, String> champs,
                                     String metadonneesJson) {
        String f = "----qa" + UUID.randomUUID();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (var e : champs.entrySet()) {
            buf.writeBytes(("--" + f + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (metadonneesJson != null) {
            buf.writeBytes(("--" + f + "\r\nContent-Disposition: form-data; name=\"metadonnees\"\r\nContent-Type: application/json\r\n\r\n"
                    + metadonneesJson + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        buf.writeBytes(("--" + f + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + nomFichier
                + "\"\r\nContent-Type: " + typeContenu + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        buf.writeBytes(contenu);
        buf.writeBytes(("\r\n--" + f + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Object[]{f, buf.toByteArray()};
    }

    public Rep deposer(String jeton, Path fichier, String nom, String typeId, String confidentialite) throws Exception {
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("name", nom);
        champs.put("typeDocumentId", typeId);
        if (confidentialite != null) champs.put("confidentialite", confidentialite);
        String n = fichier.getFileName().toString();
        String type = n.endsWith(".pdf") ? "application/pdf" : n.endsWith(".png") ? "image/png" : "application/octet-stream";
        return deposer(jeton, Files.readAllBytes(fichier), nom + n.substring(n.lastIndexOf('.')), type, champs, null);
    }
}
