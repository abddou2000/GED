package com.ipt.ged.conventionsapi;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import com.ipt.ged.fichier.CodesErreurFichier;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-E5-004 : un fichier au-delà du plafond de la plateforme reçoit 413
 * {@code FICHIER_TROP_VOLUMINEUX} en problem+json, et non 500. Rejoué dans un
 * vrai Tomcat embarqué (c'est Tomcat qui enveloppe le dépassement dans une
 * {@link IllegalStateException}), avec des plafonds réduits (1 Kio par fichier,
 * 4 Kio par requête) pour ne pas envoyer 200 Mo.
 */
class FiltreConventionsApiPlafondTest {

    private static Tomcat tomcat;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void demarrer(@TempDir Path dossier) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(dossier.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", dossier.toString());

        Wrapper servlet = Tomcat.addServlet(ctx, "cible", new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setStatus(201);
                resp.getWriter().write("ok");
            }
        });
        servlet.setMultipartConfigElement(new MultipartConfigElement(dossier.toString(), 1024, 4096, 0));
        ctx.addServletMappingDecoded("/*", "cible");

        FilterDef def = new FilterDef();
        def.setFilterName("conventions");
        def.setFilter(new FiltreConventionsApi(new ProprietesConventionsApi(65536, List.of()),
                new ReponsesSecuriteProblem(JsonMapper.builder().build())));
        ctx.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName("conventions");
        map.addURLPatternDecoded("/*");
        ctx.addFilterMap(map);

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void arreter() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    @Test
    @DisplayName("Fichier au-delà du plafond par fichier : 413 FICHIER_TROP_VOLUMINEUX (problem+json)")
    void fichierTropGros() throws Exception {
        HttpResponse<String> r = envoyer(2048);
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.body()).contains("\"code\":\"" + CodesErreurFichier.FICHIER_TROP_VOLUMINEUX + "\"");
    }

    @Test
    @DisplayName("Requête au-delà du plafond de requête : 413 FICHIER_TROP_VOLUMINEUX")
    void requeteTropGrosse() throws Exception {
        HttpResponse<String> r = envoyer(8192);
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains(CodesErreurFichier.FICHIER_TROP_VOLUMINEUX);
    }

    @Test
    @DisplayName("Fichier sous le plafond : la requête passe")
    void fichierAccepte() throws Exception {
        assertThat(envoyer(512).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Un autre échec (hors plafond) n'est pas pris pour un dépassement")
    void autreEchec() {
        assertThat(FiltreConventionsApi.plafondDepasse(new IllegalStateException("flux interrompu"))).isFalse();
    }

    private static HttpResponse<String> envoyer(int octets) throws Exception {
        String limite = "----limite" + octets;
        ByteArrayOutputStream corps = new ByteArrayOutputStream();
        corps.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"titre\"\r\n\r\nessai\r\n"
                + "--" + limite + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        corps.write("x".repeat(octets).getBytes(StandardCharsets.UTF_8));
        corps.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corps.toByteArray()))
                .build();
        return CLIENT.send(requete, HttpResponse.BodyHandlers.ofString());
    }
}
