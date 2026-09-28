package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.support.Pdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Décisions de validation tracées au journal (dossier fonctionnel §4.9.4 :
 * « décision de validation »), sur le DOCUMENT, avec le circuit, la décision
 * et le motif — depuis le workflow parallèle du lot E8 (l'ancien circuit de
 * signatures séquentielles a disparu).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class AuditDecisionsValidationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private JdbcTemplate jdbc;

    private UUID circuit, document;

    @BeforeEach
    void circuitOuvert() throws Exception {
        UUID espace = jeu.noeud("Espace audit E8", null);
        UUID type = jeu.type(espace, Confidentialite.PUBLIC);
        String regle = mvc.perform(post("/api/v1/workflow/regles").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Audit\",\"steps\":[{\"employeId\":\"" + jeu.employeId(Comptes.SECOND_ACTEUR)
                                + "\",\"label\":\"Contrôle\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/workflow/noeuds/" + espace + "/regle").contentType(APPLICATION_JSON)
                        .content("{\"regleId\":\"" + om.readTree(regle).get("id").asText() + "\"}"))
                .andExpect(status().isNoContent());
        String doc = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "facture.pdf", "application/pdf", Pdfs.pdf("audit-e8")))
                        .param("name", "Facture auditée").param("typeDocumentId", type.toString()))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        document = UUID.fromString(om.readTree(doc).get("id").asText());
        circuit = UUID.fromString(om.readTree(mvc.perform(get("/api/v1/workflow/documents/" + document + "/circuits"))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText());
    }

    private void decider(String qui, String corps, int statut) throws Exception {
        mvc.perform(post("/api/v1/workflow/circuits/" + circuit + "/decisions")
                        .with(user(identites.loadUserByUsername(qui)))
                        .contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().is(statut));
    }

    private List<Map<String, Object>> traces(String action) {
        return jdbc.queryForList("SELECT objet_type, motif, acteur_utilisateur_id, apres::text AS apres"
                + " FROM journal_audit WHERE objet_id = ? AND action = ?", document, action);
    }

    @Test
    @DisplayName("Validation : VALIDATION_APPROUVEE sur le document, avec le circuit et l'auteur")
    void approbation() throws Exception {
        decider(Comptes.SECOND_ACTEUR, "{\"decision\":\"VALIDE\",\"motif\":\"conforme\"}", 201);
        List<Map<String, Object>> t = traces("VALIDATION_APPROUVEE");
        assertThat(t).hasSize(1);
        assertThat(t.get(0)).containsEntry("objet_type", "DOCUMENT").containsEntry("motif", "conforme")
                .containsEntry("acteur_utilisateur_id", jeu.utilisateurId(Comptes.SECOND_ACTEUR));
        assertThat((String) t.get(0).get("apres")).contains(circuit.toString()).contains("VALIDE");
    }

    @Test
    @DisplayName("Refus : VALIDATION_REJETEE avec le motif")
    void rejet() throws Exception {
        decider(Comptes.SECOND_ACTEUR, "{\"decision\":\"REFUSE\",\"motif\":\"pièce illisible\"}", 201);
        assertThat(traces("VALIDATION_REJETEE")).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("motif", "pièce illisible"));
    }

    @Test
    @DisplayName("Décision refusée (pas validateur) : aucune trace de décision")
    void refus() throws Exception {
        decider(Comptes.TROISIEME_ACTEUR, "{\"decision\":\"VALIDE\"}", 403);
        assertThat(traces("VALIDATION_APPROUVEE")).isEmpty();
        assertThat(traces("VALIDATION_REJETEE")).isEmpty();
    }
}
