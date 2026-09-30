import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GED simulée pour la recette de deployer.sh / test-fumee.sh (T-092) SANS démarrer
 * l'application (ports réservés à une autre recette) : sonde de disponibilité, page
 * d'accueil durcie, config.json, connexion, dépôt, recherche, corbeille.
 *
 * <p>Le contrat de connexion reproduit celui du back-end livré :
 * {@code DemandeConnexion(identifiant @NotBlank, motDePasse @NotBlank)} — un corps
 * sans « identifiant » reçoit 400, comme la validation Spring. Le mode
 * {@code tolerant} (fichier <travail>/contrat = tolerant) accepte aussi « email »,
 * pour éprouver la suite de l'enchaînement.
 *
 * <p>État piloté par fichiers dans le répertoire de travail : {@code sante}
 * (UP/DOWN), {@code contrat} (reel/tolerant). Journal des requêtes : {@code requetes.log}.
 *
 * <p>Usage : java ServeurGedSimule.java PORT REPERTOIRE_TRAVAIL
 */
public class ServeurGedSimule {
    private static Path travail;
    private static volatile String dernierNom = "";
    private static volatile String dernierId = "";

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        travail = Path.of(args[1]);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/", ServeurGedSimule::traiter);
        s.start();
        System.out.println("GED simulée sur 127.0.0.1:" + port);
    }

    private static String etat(String nom, String defaut) {
        try {
            return Files.readString(travail.resolve(nom)).trim();
        } catch (IOException e) {
            return defaut;
        }
    }

    private static void traiter(HttpExchange x) throws IOException {
        String methode = x.getRequestMethod();
        String chemin = x.getRequestURI().getPath();
        String requete = x.getRequestURI().getRawQuery();
        byte[] corps = x.getRequestBody().readAllBytes();
        String texte = new String(corps, StandardCharsets.UTF_8);
        Files.writeString(travail.resolve("requetes.log"), methode + " " + chemin + (requete == null ? "" : "?" + requete)
                + (chemin.endsWith("/login") ? " corps=" + texte.replaceAll("\"motDePasse\":\"[^\"]*\"", "\"motDePasse\":\"***\"") : "") + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        x.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'");
        x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        if (chemin.startsWith("/actuator/health")) {
            String sante = etat("sante", "UP");
            repondre(x, "UP".equals(sante) ? 200 : 503, "{\"status\":\"" + sante + "\"}");
        } else if (chemin.equals("/")) {
            repondre(x, 200, "<!doctype html><title>GED</title>");
        } else if (chemin.equals("/assets/config.json")) {
            repondre(x, 200, "{\"demo\":false,\"apiUrl\":\"/api\"}");
        } else if (chemin.equals("/api/v1/auth/login") && methode.equals("POST")) {
            String identifiant = champ(texte, "identifiant");
            if ((identifiant == null || identifiant.isBlank()) && "tolerant".equals(etat("contrat", "reel"))) {
                identifiant = champ(texte, "email");
            }
            if (identifiant == null || identifiant.isBlank() || champ(texte, "motDePasse") == null) {
                repondre(x, 400, "{\"status\":400,\"message\":\"L'identifiant est obligatoire\"}");
            } else {
                repondre(x, 200, "{\"token\":\"jeton-simule\",\"tokenType\":\"Bearer\",\"expiresIn\":900}");
            }
        } else if (chemin.equals("/api/v1/documents") && methode.equals("POST")) {
            Matcher m = Pattern.compile("name=\"name\"\\r\\n\\r\\n([^\\r]*)").matcher(texte);
            dernierNom = m.find() ? m.group(1) : "";
            dernierId = UUID.randomUUID().toString();
            repondre(x, 201, "{\"id\":\"" + dernierId + "\",\"name\":\"" + dernierNom + "\"}");
        } else if (chemin.equals("/api/v1/documents") && methode.equals("GET")) {
            boolean trouve = requete != null && !dernierNom.isEmpty() && requete.contains(dernierNom);
            repondre(x, 200, "{\"content\":[" + (trouve ? "{\"id\":\"" + dernierId + "\"}" : "") + "],\"total\":" + (trouve ? 1 : 0) + "}");
        } else if (chemin.startsWith("/api/v1/documents/") && methode.equals("DELETE")) {
            x.sendResponseHeaders(204, -1);
            x.close();
        } else {
            repondre(x, 404, "{\"status\":404}");
        }
    }

    private static String champ(String json, String nom) {
        Matcher m = Pattern.compile("\"" + nom + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void repondre(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }
}
