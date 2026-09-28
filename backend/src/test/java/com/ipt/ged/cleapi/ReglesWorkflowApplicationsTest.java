package com.ipt.ged.cleapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gestion des règles de workflow par une application (D8, ANO-E8-001) : pour le
 * compte d'une personne ({@code X-On-Behalf-Of}), dans la portée
 * {@code WORKFLOW_PILOTAGE} de la clé sur chaque nœud où la règle s'applique, et
 * avec {@code GERER_REFERENTIELS} de la personne (intersection explicite) ;
 * double identité au journal. De bout en bout par l'API réelle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class ReglesWorkflowApplicationsTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;
    @Autowired private WorkflowRepository workflows;
    @Autowired private EmployeRepository employes;
    @Autowired private WorkSpaceRepository noeuds;

    private String suffixe;
    private UUID espace;
    private UUID horsPortee;
    private UUID application;
    private String clePilotage;
    private String cleVersement;

    @BeforeEach
    void preparer() throws Exception {
        suffixe = UUID.randomUUID().toString().substring(0, 8);
        espace = espace("Pilote");
        horsPortee = espace("Ailleurs");
        JsonNode app = json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                .content("{\"code\":\"intranet-" + suffixe + "\",\"nom\":\"Intranet\","
                        + "\"adressesAutorisees\":[\"127.0.0.1\"]}")));
        application = UUID.fromString(app.get("id").asText());
        clePilotage = cle("WORKFLOW_PILOTAGE");
        cleVersement = cle("VERSEMENT");
    }

    private UUID espace(String nom) {
        Employe e = employes.findById(jeu.employeId(Comptes.ADMIN)).orElseThrow();
        WorkflowGed wf = workflows.save(new WorkflowGed("WF " + nom + " " + suffixe));
        WorkSpace w = new WorkSpace(nom + " " + suffixe, "WS-" + nom.toUpperCase() + "-" + suffixe);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        return noeuds.saveAndFlush(w).getId();
    }

    /** Clé avec délégation, portée sur {@link #espace} limitée à l'opération donnée. */
    private String cle(String operation) throws Exception {
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + application + "/cles")
                .contentType(APPLICATION_JSON).content("{\"delegation\":true}")));
        mvc.perform(put("/api/v1/cles-api/" + g.get("details").get("id").asText() + "/portee")
                        .contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + espace + "\",\"operations\":[\"" + operation + "\"]}]}"))
                .andExpect(status().isOk());
        return g.get("cle").asText();
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder parCle(MockHttpServletRequestBuilder r, String cle, String pour) {
        r.with(anonymous()).header(FiltreCleApi.ENTETE_CLE, cle).contentType(APPLICATION_JSON);
        return pour == null ? r : r.header(FiltreCleApi.ENTETE_DELEGATION, pour);
    }

    private String regle(String nom, String etape) {
        return "{\"name\":\"" + nom + " " + suffixe + "\",\"steps\":[" + etape + "]}";
    }

    private String nomme() {
        return "{\"employeId\":\"" + jeu.employeId(Comptes.SECOND_ACTEUR) + "\",\"label\":\"Visa\",\"stepOrder\":1}";
    }

    @Test
    @DisplayName("Créer, rattacher, modifier, supprimer une règle par clé WORKFLOW_PILOTAGE pour un Administrateur délégué")
    void pilotageParApplication() throws Exception {
        // Création pour le compte de l'Administrateur : 201, double identité au journal.
        JsonNode cree = json(mvc.perform(parCle(post("/api/v1/workflow/regles"), clePilotage, Comptes.ADMIN)
                .content(regle("Règle intranet", nomme()))).andExpect(status().isCreated()));
        UUID regle = UUID.fromString(cree.get("id").asText());
        Map<String, Object> trace = jdbc.queryForMap("SELECT acteur_utilisateur_id, acteur_application_id"
                + " FROM journal_audit WHERE action = 'WORKFLOW_CREE' AND objet_id = ?", regle);
        assertThat(trace).containsEntry("acteur_utilisateur_id", jeu.utilisateurId(Comptes.ADMIN))
                .containsEntry("acteur_application_id", application);

        // Rattachement au nœud de la portée, puis modification (désignation des validateurs).
        mvc.perform(parCle(put("/api/v1/workflow/noeuds/" + espace + "/regle"), clePilotage, Comptes.ADMIN)
                .content("{\"regleId\":\"" + regle + "\"}")).andExpect(status().isNoContent());
        mvc.perform(parCle(put("/api/v1/workflow/regles/" + regle), clePilotage, Comptes.ADMIN)
                .content(regle("Règle intranet modifiée", nomme()))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'WORKFLOW_MODIFIE'"
                + " AND objet_id = ? AND acteur_application_id = ?", Long.class, regle, application)).isEqualTo(1);

        // Validateur par rôle : son périmètre doit être dans la portée.
        UUID role = jdbc.queryForObject("SELECT id FROM role ORDER BY id LIMIT 1", UUID.class);
        String parRole = "{\"roleId\":\"" + role + "\",\"perimetreNoeudId\":\"%s\",\"label\":\"Rôle\",\"stepOrder\":2}";
        mvc.perform(parCle(put("/api/v1/workflow/regles/" + regle), clePilotage, Comptes.ADMIN)
                        .content(regle("Règle intranet", nomme() + "," + parRole.formatted(horsPortee))))
                .andExpect(status().isForbidden());
        mvc.perform(parCle(put("/api/v1/workflow/regles/" + regle), clePilotage, Comptes.ADMIN)
                        .content(regle("Règle intranet", nomme() + "," + parRole.formatted(espace))))
                .andExpect(status().isOk());

        // Suppression : dans la portée, pour le compte de l'Administrateur.
        mvc.perform(parCle(put("/api/v1/workflow/noeuds/" + espace + "/regle"), clePilotage, Comptes.ADMIN)
                .content("{\"regleId\":null}")).andExpect(status().isNoContent());
        mvc.perform(parCle(delete("/api/v1/workflow/regles/" + regle), clePilotage, Comptes.ADMIN))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("Refus : sans délégation, délégué non administrateur, clé sans WORKFLOW_PILOTAGE, règle hors portée")
    void refus() throws Exception {
        mvc.perform(parCle(post("/api/v1/workflow/regles"), clePilotage, null).content(regle("Sans délégué", nomme())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DELEGATION_REQUISE"));
        // Intersection : la portée de la clé ne donne pas à la personne un droit qu'elle n'a pas.
        mvc.perform(parCle(post("/api/v1/workflow/regles"), clePilotage, Comptes.SECOND_ACTEUR)
                        .content(regle("Non administrateur", nomme())))
                .andExpect(status().isForbidden());
        // Une clé de versement (Consulter + Modifier) ne pilote pas le workflow.
        mvc.perform(parCle(post("/api/v1/workflow/regles"), cleVersement, Comptes.ADMIN)
                        .content(regle("Versement", nomme())))
                .andExpect(status().isForbidden());
        // Règle appliquée hors de la portée de la clé : ni modifiable ni supprimable par l'application.
        UUID ailleurs = jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, horsPortee);
        mvc.perform(parCle(put("/api/v1/workflow/regles/" + ailleurs), clePilotage, Comptes.ADMIN)
                .content(regle("Détournée", nomme()))).andExpect(status().isForbidden());
        mvc.perform(parCle(delete("/api/v1/workflow/regles/" + ailleurs), clePilotage, Comptes.ADMIN))
                .andExpect(status().isForbidden());
        // Opérations de masse : réservées à l'interface.
        mvc.perform(parCle(delete("/api/v1/workflow/regles/multiple-delete"), clePilotage, Comptes.ADMIN)
                .content("{\"ids\":[\"" + ailleurs + "\"]}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT supprime FROM regle_workflow WHERE id = ?", Boolean.class, ailleurs))
                .isFalse();
    }
}
