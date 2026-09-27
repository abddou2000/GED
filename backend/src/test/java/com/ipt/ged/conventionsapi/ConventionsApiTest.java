package com.ipt.ged.conventionsapi;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import com.ipt.ged.support.Comptes;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conventions communes (DAT §5.3.2) : pagination {@code page}/{@code taille}
 * (défaut 50, maximum 200), métadonnées limitées à 64 Ko, en-têtes de
 * dépréciation d'une version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class ConventionsApiTest {

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("Pagination : taille par défaut 50, alias taille, plafond 200")
    void pagination() throws Exception {
        mvc.perform(get("/api/v1/etiquettes")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/api/v1/etiquettes").param("taille", "7")).andExpect(jsonPath("$.size").value(7));
        mvc.perform(get("/api/v1/etiquettes").param("taille", "5000")).andExpect(jsonPath("$.size").value(200));
        // Le nom historique reste accepté (écrans existants).
        mvc.perform(get("/api/v1/etiquettes").param("size", "12")).andExpect(jsonPath("$.size").value(12));
    }

    @Test
    @DisplayName("Métadonnées au-delà de 64 Ko : 413 METADONNEES_TROP_VOLUMINEUSES")
    void metadonnees() throws Exception {
        String grand = "{\"code\":\"TAG-X\",\"tag\":\"" + "x".repeat(70_000) + "\",\"couleur\":\"#000000\"}";
        mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON).content(grand))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(FiltreConventionsApi.METADONNEES_TROP_VOLUMINEUSES));
    }

    @Test
    @DisplayName("Dépréciation annoncée : Deprecation, Sunset et lien successor-version sur les chemins concernés")
    void depreciation() throws Exception {
        var d = new ProprietesConventionsApi.Depreciation("/api/v1/", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2028-01-01"), "/api/v2/");
        var filtre = new FiltreConventionsApi(new ProprietesConventionsApi(65536, List.of(d)),
                new ReponsesSecuriteProblem(JsonMapper.builder().build()));

        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(new MockHttpServletRequest("GET", "/api/v1/documents"), reponse, new MockFilterChain());
        assertThat(reponse.getHeader("Deprecation")).isEqualTo("@1798761600");
        assertThat(reponse.getHeader("Sunset")).isEqualTo("Sat, 1 Jan 2028 00:00:00 GMT");
        assertThat(reponse.getHeader("Link")).isEqualTo("</api/v2/>; rel=\"successor-version\"");

        MockHttpServletResponse autre = new MockHttpServletResponse();
        filtre.doFilter(new MockHttpServletRequest("GET", "/api/v2/documents"), autre, new MockFilterChain());
        assertThat(autre.getHeader("Deprecation")).isNull();

        // Maintien d'au moins 12 mois après l'annonce (§5.3.2).
        assertThatThrownBy(() -> new ProprietesConventionsApi.Depreciation("/api/v1/", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-06-01"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Alias taille : une écriture n'est pas touchée")
    void ecritureNonTouchee() throws Exception {
        var filtre = new FiltreConventionsApi(new ProprietesConventionsApi(null, null),
                new ReponsesSecuriteProblem(JsonMapper.builder().build()));
        AtomicReference<HttpServletRequest> vue = new AtomicReference<>();
        filtre.doFilter(new MockHttpServletRequest("POST", "/api/v1/etiquettes"), new MockHttpServletResponse(),
                (req, rep) -> vue.set((HttpServletRequest) req));
        assertThat(vue.get().getParameter("size")).isNull();
    }
}
