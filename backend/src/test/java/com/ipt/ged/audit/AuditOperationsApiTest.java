package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaque opération d'administration des référentiels produit son événement
 * d'audit (DAT §7.4.1) : valeurs avant et après pour une modification, une
 * trace par objet pour une opération de masse, rien pour une opération refusée
 * (l'action n'a pas eu lieu).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class AuditOperationsApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;

    private UUID creerEtiquette(String code, String tag) throws Exception {
        String res = mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"tag\":\"" + tag + "\",\"couleur\":\"#c0392b\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    private List<Map<String, Object>> traces(UUID objet) {
        return jdbc.queryForList("SELECT action, resultat, acteur_nom, avant::text AS avant, apres::text AS apres"
                + " FROM journal_audit WHERE objet_id = ? ORDER BY id", objet);
    }

    @Test
    @DisplayName("Étiquette : création, modification (champs modifiés seuls), corbeille, restauration")
    void cycleDeVie() throws Exception {
        UUID id = creerEtiquette("AUD-1", "Urgent");
        mvc.perform(put("/api/v1/etiquettes/" + id).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"AUD-1\",\"tag\":\"Très urgent\",\"couleur\":\"#c0392b\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/etiquettes/" + id)).andExpect(status().is2xxSuccessful());
        mvc.perform(patch("/api/v1/etiquettes/" + id + "/restore")).andExpect(status().is2xxSuccessful());

        List<Map<String, Object>> t = traces(id);
        assertThat(t).extracting(m -> m.get("action"))
                .containsExactly("ETIQUETTE_CREEE", "ETIQUETTE_MODIFIEE", "ETIQUETTE_SUPPRIMEE", "ETIQUETTE_RESTAUREE");
        assertThat(t).allSatisfy(m -> {
            assertThat(m.get("resultat")).isEqualTo("SUCCES");
            assertThat(m.get("acteur_nom")).isEqualTo(Comptes.ADMIN);
        });
        assertThat((String) t.get(0).get("apres")).contains("\"tag\": \"Urgent\"");
        // Modification : seul le libellé a changé.
        assertThat(om.readTree((String) t.get(1).get("avant")).properties()).hasSize(1);
        assertThat((String) t.get(1).get("avant")).contains("\"tag\": \"Urgent\"");
        assertThat((String) t.get(1).get("apres")).contains("\"tag\": \"Très urgent\"");
    }

    @Test
    @DisplayName("Opération de masse : une trace par objet réellement traité")
    void operationDeMasse() throws Exception {
        UUID a = creerEtiquette("AUD-M1", "Un");
        UUID b = creerEtiquette("AUD-M2", "Deux");
        mvc.perform(delete("/api/v1/etiquettes/" + b)).andExpect(status().is2xxSuccessful());

        mvc.perform(delete("/api/v1/etiquettes/multiple-delete").contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(traces(a)).extracting(m -> m.get("action")).containsExactly("ETIQUETTE_CREEE", "ETIQUETTE_SUPPRIMEE");
        // b était déjà en corbeille : la suppression groupée ne l'a pas touché.
        assertThat(traces(b)).extracting(m -> m.get("action")).containsExactly("ETIQUETTE_CREEE", "ETIQUETTE_SUPPRIMEE");
    }

    @Test
    @DisplayName("Opération refusée : aucune trace de succès")
    void refusSansTrace() throws Exception {
        creerEtiquette("AUD-D", "Doublon");
        long avant = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'ETIQUETTE_CREEE'", Long.class);
        mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"AUD-D\",\"tag\":\"Autre\",\"couleur\":\"#000000\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'ETIQUETTE_CREEE'", Long.class))
                .isEqualTo(avant);
    }

    @Test
    @DisplayName("Espace : déplacement (ancien et nouveau parent) et archivage tracés")
    void espace() throws Exception {
        UUID admin = Comptes.idAdmin(employeRepository);
        WorkflowGed wf = new WorkflowGed("WF audit");
        wf.addStep(new WorkflowStep(employeRepository.findById(admin).orElseThrow(), "Validation", 1));
        UUID workflow = workflowRepository.save(wf).getId();
        String corps = "{\"name\":\"%s\",\"code\":\"%s\",\"employeId\":\"" + admin + "\",\"workflowId\":\"" + workflow + "\"}";
        UUID parent = UUID.fromString(om.readTree(mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                .content(String.format(corps, "Parent audit", "WS-AUD-P"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("id").asText());
        UUID enfant = UUID.fromString(om.readTree(mvc.perform(post("/api/v1/workspaces").contentType(APPLICATION_JSON)
                .content(String.format(corps, "Enfant audit", "WS-AUD-E"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("id").asText());

        mvc.perform(patch("/api/v1/workspaces/" + enfant + "/parent").contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"" + parent + "\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/workspaces/" + enfant + "/archive")).andExpect(status().isOk());

        List<Map<String, Object>> t = traces(enfant);
        assertThat(t).extracting(m -> m.get("action")).containsExactly("ESPACE_CREE", "ESPACE_DEPLACE", "ESPACE_ARCHIVE");
        assertThat((String) t.get(1).get("avant")).contains("\"parentId\": null");
        assertThat((String) t.get(1).get("apres")).contains(parent.toString());
    }
}
