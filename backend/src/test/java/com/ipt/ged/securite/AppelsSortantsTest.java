package com.ipt.ged.securite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revue SSRF (OWASP A10, P-12), rendue vérifiable : l'inventaire des appels
 * sortants de {@code docs/securite/REVUE-SSRF.md} est figé ici.
 *
 * <ol>
 *   <li>Seules les classes recensées ouvrent une connexion ou lancent un
 *       processus ; un nouvel appel sortant fait échouer ce test tant qu'il n'a
 *       pas été revu et ajouté à l'inventaire (et à la revue).</li>
 *   <li>Aucune de ces classes ne reçoit d'entrée de requête HTTP : ni
 *       {@code HttpServletRequest}, ni fichier envoyé, ni paramètre de
 *       contrôleur. Leurs destinations (hôte, port, URL, commande) viennent de
 *       la configuration d'exploitation.</li>
 *   <li>Aucun client HTTP générique (RestTemplate, WebClient, HttpClient,
 *       {@code URL.openConnection}) dans le code : la GED n'appelle aucune URL
 *       fournie par un utilisateur, et n'en appelle aucune du tout.</li>
 * </ol>
 */
class AppelsSortantsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Ouverture de connexion ou lancement de processus. */
    private static final Pattern PUITS = Pattern.compile(
            "new Socket\\(|InetSocketAddress\\(|new ProcessBuilder\\(|Runtime\\.getRuntime\\(\\)\\.exec"
            + "|java\\.net\\.http\\.HttpClient|RestTemplate|WebClient|RestClient|openConnection\\(|new URL\\("
            + "|URL\\.of\\(|new InitialDirContext|getReadOnlyContext\\(|JavaMailSender|SocketChannel|DatagramSocket");

    /** Clients HTTP génériques : interdits partout. */
    private static final Pattern CLIENT_HTTP = Pattern.compile(
            "java\\.net\\.http\\.HttpClient|RestTemplate|WebClient|RestClient|openConnection\\(|new URL\\(|URL\\.of\\(");

    /** Entrées de requête : interdites dans les classes qui ouvrent une connexion. */
    private static final Pattern ENTREE_REQUETE = Pattern.compile(
            "HttpServletRequest|MultipartFile|@RequestParam|@PathVariable|@RequestBody|@RequestHeader");

    /** Inventaire revu (docs/securite/REVUE-SSRF.md) : classe → destination et origine de la destination. */
    private static final Map<String, String> INVENTAIRE = Map.of(
            "com/ipt/ged/fichier/controle/ClientClamd.java", "clamd (TCP) : ged.fichiers.antivirus.hote/port",
            "com/ipt/ged/supervision/SondeAntivirus.java", "clamd (TCP, sonde) : ged.fichiers.antivirus.hote/port",
            "com/ipt/ged/identite/annuaire/AnnuaireLdap.java", "contrôleurs de domaine (LDAPS) : GED_LDAP_URLS",
            "com/ipt/ged/identite/annuaire/ConfigurationAnnuaire.java", "contrôleurs de domaine (LDAPS) : GED_LDAP_URLS",
            "com/ipt/ged/identite/annuaire/SondeAnnuaire.java", "contrôleurs de domaine (LDAPS, sonde) : GED_LDAP_URLS",
            "com/ipt/ged/notification/ExpediteurCourriels.java", "relais SMTP : GED_SMTP_HOTE/PORT",
            "com/ipt/ged/fichier/previsualisation/ConvertisseurLibreOffice.java", "processus soffice : GED_LIBREOFFICE",
            "com/ipt/ged/ocr/moteur/MoteurTesseract.java", "processus tesseract : GED_TESSERACT");

    private static Map<String, String> sources() throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(SOURCES)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                m.put(SOURCES.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    @Test
    @DisplayName("Seules les classes de l'inventaire ouvrent une connexion ou lancent un processus")
    void inventaireFerme() throws IOException {
        List<String> hors = new ArrayList<>();
        sources().forEach((fichier, code) -> {
            if (PUITS.matcher(code).find() && !INVENTAIRE.containsKey(fichier)) hors.add(fichier);
        });
        assertThat(hors).as("Appel sortant non revu : l'ajouter à docs/securite/REVUE-SSRF.md et à l'inventaire").isEmpty();
    }

    @Test
    @DisplayName("Aucune destination construite depuis une entrée de requête ; aucun client HTTP générique")
    void destinationsDeConfiguration() throws IOException {
        Map<String, String> code = sources();
        for (String fichier : INVENTAIRE.keySet()) {
            assertThat(code).as(fichier).containsKey(fichier);
            assertThat(ENTREE_REQUETE.matcher(code.get(fichier)).find()).as(fichier + " : entrée de requête").isFalse();
        }
        List<String> clients = new ArrayList<>();
        code.forEach((fichier, c) -> { if (CLIENT_HTTP.matcher(c).find()) clients.add(fichier); });
        assertThat(clients).as("client HTTP générique").isEmpty();
    }
}
