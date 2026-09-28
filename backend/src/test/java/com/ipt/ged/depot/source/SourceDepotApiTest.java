package com.ipt.ged.depot.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.Pdfs;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Source et canal du dépôt (T-040) : enregistrés au dépôt, rendus par la fiche,
 * filtrables en recherche multicritère, protégés en base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class SourceDepotApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private UUID type;

    @BeforeEach
    void preparer() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF source " + s));
        WorkSpace w = new WorkSpace("Source " + s, "WS-SRC-" + s);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);
        TypeDocument t = new TypeDocument("TD-SRC-" + s, "Courrier " + s);
        t.setDescription("desc");
        t.setWorkspace(w);
        t.setTypeAutorise("pdf");
        t.setTailleMaxMo(5);
        type = typeRepository.save(t).getId();
    }

    @Test
    @DisplayName("Dépôt par l'interface : canal INTERFACE et déposant enregistrés, rendus par la fiche")
    void depotInterface() throws Exception {
        JsonNode r = om.readTree(mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "c.pdf", "application/pdf", Pdfs.pdf()))
                        .param("typeDocumentId", type.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        UUID id = UUID.fromString(r.get("id").asText());
        UUID admin = jdbc.queryForObject("SELECT id FROM utilisateur WHERE employe_id = ?", UUID.class,
                Comptes.idAdmin(employeRepository));
        assertEquals("INTERFACE", r.get("canalDepot").asText());
        assertEquals(admin.toString(), r.get("deposantUtilisateurId").asText());
        assertFalse(r.get("depotDelegue").asBoolean());
        assertTrue(r.get("applicationId").isNull());

        mvc.perform(get("/api/v1/documents/" + id))
                .andExpect(jsonPath("$.canalDepot").value("INTERFACE"))
                .andExpect(jsonPath("$.deposantUtilisateurId").value(admin.toString()));

        String interfaceWeb = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"canal\":\"INTERFACE\"}")).andReturn().getResponse()
                .getContentAsString();
        assertTrue(interfaceWeb.contains(id.toString()));
        String api = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"canal\":\"API\"}")).andReturn().getResponse()
                .getContentAsString();
        assertFalse(api.contains(id.toString()));

        // En base : pas de délégation sans application ni personne désignée.
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE document SET depot_delegue = true, deposant_utilisateur_id = NULL WHERE id = ?", id));
        assertThrows(Exception.class, () -> jdbc.update("UPDATE document SET canal_depot = 'COURRIEL' WHERE id = ?", id));
    }
}
