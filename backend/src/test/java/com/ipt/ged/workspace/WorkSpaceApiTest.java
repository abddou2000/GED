package com.ipt.ged.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Campagne de tests du module « Espaces de travail » (Phase 1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class WorkSpaceApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private com.ipt.ged.workspace.archivage.ArchivageNoeuds archivage;
    @Autowired private com.ipt.ged.identite.UtilisateurRepository utilisateurs;

    private static final String BASE = "/api/v1/workspaces";
    private UUID workflowId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF test");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowId = workflowRepository.save(wf).getId();
    }

    private String ws(String name, String code, UUID parentId) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"employeId\":\"" + Comptes.idAdmin(employeRepository)
                + "\",\"workflowId\":\"" + workflowId + "\""
                + (parentId != null ? ",\"parentId\":\"" + parentId + "\"" : "") + "}";
    }

    private UUID create(String name, String code, UUID parentId) throws Exception {
        String body = mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws(name, code, parentId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(body).get("id").asText());
    }

    @Test
    @DisplayName("1. Création d'un espace de travail (201)")
    void createWorkspace() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws("Comptabilité", "WS-A", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Comptabilité"))
                .andExpect(jsonPath("$.code").value("WS-A"))
                .andExpect(jsonPath("$.status").value("ACTIF"))
                .andExpect(jsonPath("$.owner.label").value("Sara Bennani"))
                .andExpect(jsonPath("$.workflow.id").value(workflowId.toString()));
    }

    @Test
    @DisplayName("2. Code en double refusé (400)")
    void duplicateCode() throws Exception {
        create("A", "WS-DUP", null);
        mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(ws("B", "WS-DUP", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("déjà utilisé")));
    }

    @Test
    @DisplayName("3. Nom manquant refusé (400)")
    void missingName() throws Exception {
        mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"WS-X\",\"employeId\":\"" + Comptes.idAdmin(employeRepository)
                                + "\",\"workflowId\":\"" + workflowId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erreurs.name").exists());
    }

    @Test
    @DisplayName("4. Déplacement : un dossier ne peut pas être son propre parent (400)")
    void moveSelfParent() throws Exception {
        UUID id = create("Racine", "WS-R", null);
        mvc.perform(patch(BASE + "/" + id + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Déplacement : interdit dans sa propre descendance (400)")
    void moveIntoDescendant() throws Exception {
        UUID parent = create("Parent", "WS-P", null);
        UUID child = create("Enfant", "WS-C", parent);
        mvc.perform(patch(BASE + "/" + parent + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":\"" + child + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Déplacement valide (200)")
    void moveValid() throws Exception {
        UUID a = create("A", "WS-MA", null);
        UUID b = create("B", "WS-MB", null);
        mvc.perform(patch(BASE + "/" + b + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":\"" + a + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.id").value(a.toString()));
    }

    @Test
    @DisplayName("7. Archivage : ACTIF <-> ARCHIVE")
    void archiveToggle() throws Exception {
        UUID id = create("Arch", "WS-ARCH", null);
        mvc.perform(patch(BASE + "/" + id + "/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVE"));
        mvc.perform(patch(BASE + "/" + id + "/archive"))
                .andExpect(jsonPath("$.status").value("ACTIF"));
    }

    @Test
    @DisplayName("8. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        UUID id = create("Del", "WS-DEL", null);
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }

    @Test
    @DisplayName("ANO-E7-005 : aucun sous-dossier créé ni déplacé sous un dossier archivé (409 DOSSIER_ARCHIVE)")
    void dossierArchiveEnLectureSeule() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        UUID archive = create("Archivé " + s, "WS-ARC-" + s, null);
        UUID sousArchive = create("Sous-archivé " + s, "WS-ARS-" + s, archive);
        UUID libre = create("Libre " + s, "WS-LIB-" + s, null);
        archivage.marquerArchive(archive, utilisateurs.findByIdentifiant(Comptes.ADMIN).orElseThrow().getId());

        // Création sous le dossier archivé, et sous un descendant (drapeau de la sous-arborescence).
        for (UUID parent : new UUID[]{archive, sousArchive}) {
            mvc.perform(post(BASE).contentType(APPLICATION_JSON)
                            .content(ws("Nouveau " + s, "WS-NV-" + s, parent)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DOSSIER_ARCHIVE"));
        }
        // Déplacement dessous : par l'action de déplacement et par la fiche.
        mvc.perform(patch(BASE + "/" + libre + "/parent").contentType(APPLICATION_JSON)
                        .content("{\"parentId\":\"" + archive + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_ARCHIVE"));
        mvc.perform(put(BASE + "/" + libre).contentType(APPLICATION_JSON)
                        .content(ws("Libre " + s, "WS-LIB-" + s, sousArchive)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_ARCHIVE"));
        mvc.perform(get(BASE + "/" + libre)).andExpect(jsonPath("$.parent").doesNotExist());
        // Rien n'a été créé sous le dossier archivé ; un dossier actif reste utilisable.
        mvc.perform(get(BASE).param("search", "Nouveau " + s)).andExpect(jsonPath("$.content.length()").value(0));
        create("Enfant libre " + s, "WS-ENL-" + s, libre);
    }

    @Test
    @DisplayName("9. Arbre : racines + enfants imbriqués")
    void tree() throws Exception {
        UUID root = create("Root", "WS-ROOT", null);
        create("Sub", "WS-SUB", root);
        mvc.perform(get(BASE + "/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..name", hasItems("Root", "Sub")));
    }
}
