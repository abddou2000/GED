package com.ipt.ged.journalisation;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.TomcatWebServerFactoryCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-E2-001 : derrière NGINX, {@code getRemoteAddr()} — utilisé par la
 * limitation des connexions et l'adresse enregistrée en session — doit rendre
 * l'adresse du CLIENT, et seulement quand la requête vient d'un proxy déclaré
 * de confiance ({@code ged.journalisation.proxys-de-confiance}). Un appelant
 * non déclaré qui pose lui-même {@code X-Forwarded-For} ne choisit pas
 * l'adresse vue par l'application.
 *
 * <p>Le test démarre un vrai Tomcat embarqué configuré comme en production :
 * {@code server.forward-headers-strategy: native} (valve RemoteIpValve posée par
 * Spring Boot), puis la liste des proxys de confiance du back-end. Le client
 * du test se connecte depuis 127.0.0.1.
 */
class AdresseClienteDerriereProxyTest {

    private static final String CLIENT = "203.0.113.7";

    @Test
    @DisplayName("Requête venue d'un proxy de confiance : getRemoteAddr() rend l'adresse du client (X-Forwarded-For)")
    void proxyDeConfiance() throws Exception {
        assertThat(adresseVue(List.of("127.0.0.1", "::1"), CLIENT)).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("Requête venue d'une machine non déclarée : X-Forwarded-For ignoré, adresse de connexion gardée")
    void proxyNonDeclare() throws Exception {
        assertThat(adresseVue(List.of("10.9.9.9"), CLIENT)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Aucun proxy déclaré : l'en-tête n'est jamais cru")
    void aucunProxy() throws Exception {
        assertThat(adresseVue(List.of(), CLIENT)).isEqualTo("127.0.0.1");
    }

    /** Démarre Tomcat avec la configuration du back-end et renvoie ce que voit getRemoteAddr(). */
    private static String adresseVue(List<String> proxys, String xff) throws Exception {
        TomcatServletWebServerFactory fabrique = new TomcatServletWebServerFactory(0);
        fabrique.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
        ServerProperties serveur = new ServerProperties();
        serveur.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NATIVE);
        new TomcatWebServerFactoryCustomizer(new MockEnvironment(), serveur).customize(fabrique);
        new ConfigurationProxysDeConfiance().proxysDeConfianceTomcat(proxys).customize(fabrique);
        WebServer serveurWeb = fabrique.getWebServer(contexte -> contexte.addServlet("adresse", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write(req.getRemoteAddr());
            }
        }).addMapping("/adresse"));
        serveurWeb.start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + serveurWeb.getPort() + "/adresse"))
                    .header("X-Forwarded-For", xff).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.body();
        } finally {
            serveurWeb.stop();
        }
    }
}
