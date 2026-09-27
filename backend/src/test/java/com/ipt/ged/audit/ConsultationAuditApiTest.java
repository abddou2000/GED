package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.support.Comptes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consultation, export et absence de toute écriture par l'API (DAT §7.4.3,
 * revue technique D11).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class ConsultationAuditApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private AuditService audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private UUID objetInscrit() {
        UUID objet = UUID.randomUUID();
        audit.enregistrer(EntreeAudit.de(ActionAudit.TYPE_DOCUMENT_MODIFIE, "TYPE_DOCUMENT", objet)
                .avecAvantApres(java.util.Map.of("nom", "Facture"), java.util.Map.of("nom", "Facture fournisseur")));
        audit.enregistrer(EntreeAudit.de(ActionAudit.ACCES_REFUSE, "TYPE_DOCUMENT", objet).refus("droit absent"));
        return objet;
    }

    @Test
    @DisplayName("Consultation filtrée par objet et résultat, paginée ; la consultation est elle-même tracée")
    void consultation() throws Exception {
        UUID objet = objetInscrit();
        long avant = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'AUDIT_CONSULTE'", Long.class);

        mvc.perform(get("/api/v1/audit/evenements")
                        .param("objetType", "TYPE_DOCUMENT").param("objetId", objet.toString()).param("resultat", "SUCCES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].action").value("TYPE_DOCUMENT_MODIFIE"))
                .andExpect(jsonPath("$.content[0].avant.nom").value("Facture"))
                .andExpect(jsonPath("$.content[0].apres.nom").value("Facture fournisseur"));

        mvc.perform(get("/api/v1/audit/evenements").param("objetId", objet.toString()))
                .andExpect(jsonPath("$.total").value(2));

        long apres = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'AUDIT_CONSULTE'", Long.class);
        assertThat(apres).isEqualTo(avant + 2);
        assertThat(jdbc.queryForObject("SELECT acteur_nom FROM journal_audit WHERE action = 'AUDIT_CONSULTE'"
                + " ORDER BY id DESC LIMIT 1", String.class)).isEqualTo(Comptes.ADMIN);
    }

    @Test
    @DisplayName("Export CSV et JSON : empreinte SHA-256 du fichier en en-tête, export tracé")
    void export() throws Exception {
        UUID objet = objetInscrit();

        MvcResult csv = mvc.perform(get("/api/v1/audit/export")
                        .param("format", "csv").param("objetId", objet.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        byte[] corps = csv.getResponse().getContentAsByteArray();
        assertThat(csv.getResponse().getHeader("X-Empreinte-SHA256")).isEqualTo(ConsultationAudit.sha256(corps));
        String texte = new String(corps, StandardCharsets.UTF_8);
        assertThat(texte).contains("TYPE_DOCUMENT_MODIFIE").contains("ACCES_REFUSE").contains("# Filtre");

        MvcResult js = mvc.perform(get("/api/v1/audit/export")
                        .param("format", "json").param("objetId", objet.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode doc = json.readTree(js.getResponse().getContentAsByteArray());
        assertThat(doc.path("format").asText()).isEqualTo("ged-journal-audit-v1");
        assertThat(doc.path("evenements")).hasSize(2);
        assertThat(doc.has("scellements")).isTrue();

        assertThat(jdbc.queryForObject("SELECT apres->>'empreinte' FROM journal_audit WHERE action = 'AUDIT_EXPORTE'"
                + " ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo(js.getResponse().getHeader("X-Empreinte-SHA256"));
    }

    @Test
    @DisplayName("CSV : cellule commençant par une formule neutralisée (injection CSV)")
    void injectionCsv() throws Exception {
        UUID objet = UUID.randomUUID();
        audit.enregistrer(EntreeAudit.de(ActionAudit.ESPACE_MODIFIE, "ESPACE", objet).avecMotif("=HYPERLINK(\"x\")"));
        String texte = mvc.perform(get("/api/v1/audit/export")
                        .param("format", "csv").param("objetId", objet.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(texte).contains("\"'=HYPERLINK(\"\"x\"\")\"").doesNotContain(";=HYPERLINK");
    }

    @Test
    @DisplayName("Aucune modification ni suppression par l'API ; anonyme refusé")
    void lectureSeule() throws Exception {
        mvc.perform(get("/api/v1/audit/evenements").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/audit/evenements"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHODE_NON_AUTORISEE"));
        mvc.perform(put("/api/v1/audit/evenements"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Critère invalide : 400 problem+json")
    void critereInvalide() throws Exception {
        mvc.perform(get("/api/v1/audit/evenements").param("action", "drop table"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUETE_INVALIDE"));
        mvc.perform(get("/api/v1/audit/evenements")
                        .param("du", "2026-09-27T10:00:00Z").param("au", "2026-09-27T09:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithUserDetails(Comptes.SANS_ROLE)
    @DisplayName("Sans la permission CONSULTER_AUDIT : consultation et export refusés (403)")
    void reserveAuxDetenteursDeConsulterAudit() throws Exception {
        mvc.perform(get("/api/v1/audit/evenements")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/export").param("format", "json")).andExpect(status().isForbidden());
    }
}
