package com.ipt.ged.journalisation;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le MDC de chaque requête porte username, ip, traceId et spanId (DAT 7.1),
 * et il est vidé à la fin de la requête.
 */
class FiltreContexteRequeteTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    private final FiltreContexteRequete filtre = new FiltreContexteRequete(List.of("127.0.0.1", "10.10.0.0/16"));
    private final FiltreUtilisateurJournalisation filtreUtilisateur = new FiltreUtilisateurJournalisation();

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** Exécute la requête à travers le filtre et renvoie le MDC vu par la suite de la chaîne. */
    private Map<String, String> mdcPendant(MockHttpServletRequest requete) throws Exception {
        AtomicReference<Map<String, String>> vu = new AtomicReference<>();
        FilterChain suite = (req, rep) -> vu.set(new HashMap<>(MDC.getCopyOfContextMap()));
        filtre.doFilter(requete, new MockHttpServletResponse(), suite);
        return vu.get();
    }

    @Test
    @DisplayName("Requête sans en-tête : trace générée, IP distante, username « - »")
    void contexteGenere() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/api/v1/documents");
        requete.setRemoteAddr("192.168.1.20");

        Map<String, String> mdc = mdcPendant(requete);

        assertThat(mdc.get("traceId")).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(mdc.get("spanId")).matches("[0-9a-f]{16}");
        assertThat(mdc.get("ip")).isEqualTo("192.168.1.20");
        assertThat(mdc.get("username")).isEqualTo("-");
    }

    @Test
    @DisplayName("Le MDC est vidé en fin de requête, même en cas d'exception")
    void nettoyageEnFinDeRequete() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        filtre.doFilter(requete, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        FilterChain enEchec = (req, rep) -> { throw new IllegalStateException("boum"); };
        try {
            filtre.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), enEchec);
        } catch (IllegalStateException attendu) {
            // l'exception remonte, le MDC doit malgré tout être vide
        }
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("username")).isNull();
        assertThat(MDC.get("ip")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }

    @Test
    @DisplayName("traceparent W3C valide : le traceId est repris, le spanId est neuf")
    void propagationTraceparent() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        requete.addHeader("traceparent", "00-" + TRACE + "-" + PARENT + "-01");

        Map<String, String> mdc = mdcPendant(requete);

        assertThat(mdc.get("traceId")).isEqualTo(TRACE);
        assertThat(mdc.get("spanId")).matches("[0-9a-f]{16}").isNotEqualTo(PARENT);
    }

    @Test
    @DisplayName("traceparent invalide ou forgé : ignoré, une nouvelle trace est générée")
    void traceparentInvalide() throws Exception {
        for (String forge : List.of("00-" + "0".repeat(32) + "-" + PARENT + "-01",
                "ff-" + TRACE + "-" + PARENT + "-01",
                "00-" + TRACE + "-" + "0".repeat(16) + "-01",
                "00-" + TRACE + "-" + PARENT + "-01-suite",
                "n'importe quoi\nFAUSSE LIGNE")) {
            var requete = new MockHttpServletRequest("GET", "/x");
            requete.addHeader("traceparent", forge);
            assertThat(mdcPendant(requete).get("traceId")).as(forge)
                    .matches("[0-9a-f]{32}").isNotEqualTo(TRACE);
        }
    }

    @Test
    @DisplayName("X-Forwarded-For posé par un proxy de confiance : adresse du client retenue")
    void xffDeConfiance() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        requete.setRemoteAddr("127.0.0.1");
        // La première entrée vient du client (non prouvée), la dernière de NGINX.
        requete.addHeader("X-Forwarded-For", "6.6.6.6, 41.250.12.3");
        assertThat(mdcPendant(requete).get("ip")).isEqualTo("41.250.12.3");

        var parPlage = new MockHttpServletRequest("GET", "/x");
        parPlage.setRemoteAddr("10.10.4.2");
        parPlage.addHeader("X-Forwarded-For", "41.250.12.3");
        assertThat(mdcPendant(parPlage).get("ip")).isEqualTo("41.250.12.3");
    }

    @Test
    @DisplayName("X-Forwarded-For d'un appelant non déclaré : ignoré (usurpation d'adresse)")
    void xffNonDeConfiance() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        requete.setRemoteAddr("192.168.1.20");
        requete.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(mdcPendant(requete).get("ip")).isEqualTo("192.168.1.20");
    }

    @Test
    @DisplayName("X-Forwarded-For illisible : l'adresse du proxy est retenue, rien n'est injecté")
    void xffIllisible() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        requete.setRemoteAddr("127.0.0.1");
        requete.addHeader("X-Forwarded-For", "1.2.3.4, ] [admin] [");
        assertThat(mdcPendant(requete).get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Appelant authentifié : username = identité établie par Spring Security")
    void usernameAuthentifie() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        AtomicReference<String> vu = new AtomicReference<>();
        FilterChain securiteEtApplication = (req, rep) -> {
            // Ce que fait la chaîne Spring Security, placée entre les deux filtres.
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("a.benali", null, List.of()));
            filtreUtilisateur.doFilter(req, rep, (r2, p2) -> vu.set(MDC.get("username")));
        };
        filtre.doFilter(requete, new MockHttpServletResponse(), securiteEtApplication);

        assertThat(vu.get()).isEqualTo("a.benali");
        assertThat(MDC.get("username")).isNull();
    }

    @Test
    @DisplayName("Appelant anonyme : username reste « - »")
    void usernameAnonyme() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        AtomicReference<String> vu = new AtomicReference<>();
        FilterChain chaine = (req, rep) -> {
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "cle", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
            filtreUtilisateur.doFilter(req, rep, (r2, p2) -> vu.set(MDC.get("username")));
        };
        filtre.doFilter(requete, new MockHttpServletResponse(), chaine);
        assertThat(vu.get()).isEqualTo("-");
    }

    @Test
    @DisplayName("Rendu d'erreur de la même requête : même traceId, même utilisateur")
    void dispatchErreurGardeLeContexte() throws Exception {
        var requete = new MockHttpServletRequest("GET", "/x");
        AtomicReference<String> traceInitiale = new AtomicReference<>();
        filtre.doFilter(requete, new MockHttpServletResponse(), (req, rep) -> {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("a.benali", null, List.of()));
            filtreUtilisateur.doFilter(req, rep, (r2, p2) -> traceInitiale.set(MDC.get("traceId")));
        });
        SecurityContextHolder.clearContext();

        // Tomcat rejoue la chaîne pour /error, sans contexte de sécurité.
        requete.setDispatcherType(DispatcherType.ERROR);
        Map<String, String> mdc = mdcPendant(requete);

        assertThat(mdc.get("traceId")).isEqualTo(traceInitiale.get());
        assertThat(mdc.get("username")).isEqualTo("a.benali");
    }
}
