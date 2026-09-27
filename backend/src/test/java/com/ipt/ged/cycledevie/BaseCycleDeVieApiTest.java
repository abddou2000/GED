package com.ipt.ged.cycledevie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Socle des tests d'API du cycle de vie : sans transaction de test (les
 * opérations valident réellement leurs transactions et détruisent de vrais
 * fichiers), données repérées par des codes aléatoires.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
abstract class BaseCycleDeVieApiTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WorkflowRepository workflowRepository;
    @Autowired protected EmployeRepository employeRepository;
    @Autowired protected WorkSpaceRepository workspaceRepository;
    @Autowired protected TypeDocumentRepository typeRepository;
    @Value("${ged.fichiers.racine}") protected String racineStockage;

    protected final String suffixe = UUID.randomUUID().toString().substring(0, 8);

    protected WorkSpace espace(String nom, WorkSpace parent) {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF " + nom + " " + suffixe));
        WorkSpace w = new WorkSpace(nom, "WS-" + UUID.randomUUID().toString().substring(0, 12));
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        w.setParent(parent);
        return workspaceRepository.save(w);
    }

    protected UUID type(WorkSpace w, String formats) {
        TypeDocument t = new TypeDocument("TD-" + UUID.randomUUID().toString().substring(0, 12), "Pièce " + suffixe);
        t.setDescription("desc");
        t.setWorkspace(w);
        t.setTypeAutorise(formats);
        t.setTailleMaxMo(5);
        return typeRepository.save(t).getId();
    }

    protected UUID deposer(UUID type, String nom, String fichier, String mime, byte[] contenu) throws Exception {
        String r = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", fichier, mime, contenu))
                        .param("name", nom).param("typeDocumentId", type.toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(r).get("id").asText());
    }

    protected JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Corps d'une réponse servie en flux. */
    protected byte[] flux(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    protected UUID fichierCourant(UUID documentId) {
        return jdbc.queryForObject("SELECT cle_fichier_id FROM version_document WHERE document_id = ? AND is_default",
                UUID.class, documentId);
    }

    /** Emplacement du fichier chiffré ({@code aa/bb/<uuid>.enc}). */
    protected Path fichierChiffre(UUID id) {
        String s = id.toString();
        return Paths.get(racineStockage).toAbsolutePath().normalize()
                .resolve(s.substring(0, 2)).resolve(s.substring(2, 4)).resolve(s + ".enc");
    }
}
