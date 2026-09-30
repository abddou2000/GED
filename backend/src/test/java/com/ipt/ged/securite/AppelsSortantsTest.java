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

    /**
     * Ouverture de connexion (socket, fabrique de sockets, contexte ou client
     * LDAP, courriel, JDBC direct), écoute réseau, résolution de nom ou
     * lancement de processus.
     */
    private static final Pattern PUITS = Pattern.compile(
            "new Socket\\(|InetSocketAddress\\(|\\.connect\\(|createSocket\\(|SocketFactory|ServerSocket"
            + "|SocketChannel|DatagramSocket|InetAddress\\.getBy|InetAddress\\.getAllBy"
            + "|new ProcessBuilder\\(|Runtime\\.getRuntime\\(\\)\\.exec"
            + "|java\\.net\\.http\\.HttpClient|RestTemplate|WebClient|RestClient|openConnection\\(|URLConnection"
            + "|new URL\\(|URL\\.of\\(|\\.toURL\\(\\)"
            + "|InitialDirContext|InitialLdapContext|new InitialContext|getReadOnlyContext\\(|LdapContextSource"
            + "|LdapTemplate|LDAPConnection|InMemoryDirectoryServer"
            + "|JavaMailSender|jakarta\\.mail\\.Transport|DriverManager\\.getConnection");

    /** Clients HTTP génériques : interdits partout. */
    private static final Pattern CLIENT_HTTP = Pattern.compile(
            "java\\.net\\.http\\.HttpClient|RestTemplate|WebClient|RestClient|openConnection\\(|URLConnection"
            + "|new URL\\(|URL\\.of\\(|\\.toURL\\(\\)");

    /** Entrées de requête : interdites dans les classes qui ouvrent une connexion. */
    private static final Pattern ENTREE_REQUETE = Pattern.compile(
            "HttpServletRequest|MultipartFile|@RequestParam|@PathVariable|@RequestBody|@RequestHeader");

    /** Inventaire revu (docs/securite/REVUE-SSRF.md) : classe → destination et origine de la destination. */
    private static final Map<String, String> INVENTAIRE = Map.ofEntries(
            Map.entry("com/ipt/ged/fichier/controle/ClientClamd.java", "clamd (TCP) : ged.fichiers.antivirus.hote/port"),
            Map.entry("com/ipt/ged/supervision/SondeAntivirus.java", "clamd (TCP, sonde) : ged.fichiers.antivirus.hote/port"),
            Map.entry("com/ipt/ged/identite/annuaire/AnnuaireLdap.java", "contrôleurs de domaine (LDAPS) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/ConfigurationAnnuaire.java", "contrôleurs de domaine (LDAPS) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/ControleursAnnuaire.java",
                    "contrôleurs de domaine (LDAPS, une source par contrôleur, bascule D4) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/EcheanceSecretAnnuaire.java",
                    "contrôleurs de domaine (LDAPS, entrée du compte de service lui-même, échéance du secret, P-02) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/EtatCompteAnnuaireLdap.java",
                    "contrôleurs de domaine (LDAPS, userAccountControl à la délégation, D15) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/FabriqueSocketsLdaps.java",
                    "sockets TLS vers les contrôleurs de domaine, hôte et port fournis par JNDI depuis GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/SondeAnnuaire.java", "contrôleurs de domaine (LDAPS, sonde) : GED_LDAP_URLS"),
            Map.entry("com/ipt/ged/identite/annuaire/SimulateurAnnuaire.java",
                    "écoute sur la boucle locale (annuaire simulé des profils dev et test), aucun appel sortant"),
            Map.entry("com/ipt/ged/journalisation/ConfigurationProxysDeConfiance.java",
                    "aucune : InetAddress sur une adresse IP littérale (contrôlée), jamais de résolution DNS"),
            Map.entry("com/ipt/ged/notification/ExpediteurCourriels.java", "relais SMTP : GED_SMTP_HOTE/PORT"),
            Map.entry("com/ipt/ged/fichier/previsualisation/ConvertisseurLibreOffice.java", "processus soffice : GED_LIBREOFFICE"),
            Map.entry("com/ipt/ged/ocr/moteur/MoteurTesseract.java", "processus tesseract : GED_TESSERACT"));

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
    @DisplayName("Chaque entrée de l'inventaire est reconnue par les motifs et décrite dans la revue")
    void inventaireJustifie() throws IOException {
        // Une entrée qu'aucun motif ne reconnaît signale un motif manquant (ANO-E11-001) :
        // l'inventaire ne vaut que si le test sait retrouver ce qu'il déclare.
        Map<String, String> code = sources();
        String revue = Files.readString(Path.of("../docs/securite/REVUE-SSRF.md"), StandardCharsets.UTF_8);
        for (String fichier : INVENTAIRE.keySet()) {
            assertThat(code).as(fichier).containsKey(fichier);
            assertThat(PUITS.matcher(code.get(fichier)).find()).as(fichier + " : aucun motif ne le reconnaît").isTrue();
            String classe = fichier.substring(fichier.lastIndexOf('/') + 1).replace(".java", "");
            assertThat(revue).as(classe + " absent de REVUE-SSRF.md").contains(classe);
        }
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
