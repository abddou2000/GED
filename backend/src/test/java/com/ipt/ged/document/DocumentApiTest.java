package com.ipt.ged.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

import java.util.UUID;

/**
 * Campagne de tests du dépôt de documents (Phase 1) : upload avec validation
 * des contraintes du type (formats + taille), téléchargement, corbeille.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class DocumentApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private static final String BASE = "/api/v1/documents";
    private UUID typeId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF test");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-DOC");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-DOC", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf,docx");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    @Test
    @DisplayName("1. Dépôt d'un fichier autorisé (201) + dossier hérité du type")
    void uploadValid() throws Exception {
        mvc.perform(multipart(BASE)
                        .file(file("facture.pdf", "contenu pdf".getBytes()))
                        .param("name", "Ma facture")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ma facture"))
                .andExpect(jsonPath("$.extension").value("pdf"))
                .andExpect(jsonPath("$.workspace.label").value("Comptabilite"))
                .andExpect(jsonPath("$.typeDocument.label").value("Facture"));
    }

    @Test
    @DisplayName("1b. Le créateur vient du jeton, pas de la requête")
    void createurNonUsurpable() throws Exception {
        /* Le dépôt est fait par l'ADMIN (Sara). On tente d'attribuer la pièce à
           quelqu'un d'autre via le paramètre historique `createdById` : il doit
           rester sans effet. Sans cette garantie, la colonne « Créateur » — qui
           sert de trace de responsabilité — serait déclarative, et n'importe
           qui pourrait déposer au nom d'un collègue. */
        mvc.perform(multipart(BASE)
                        .file(file("facture-createur.pdf", "contenu pdf".getBytes()))
                        .param("name", "Pièce tracée")
                        .param("typeDocumentId", String.valueOf(typeId))
                        .param("createdById", "999"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("Sara Bennani"));
    }

    @Test
    @DisplayName("2. Format non autorisé refusé (400)")
    void uploadWrongFormat() throws Exception {
        mvc.perform(multipart(BASE)
                        .file(file("virus.exe", "x".getBytes()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("non autorisé")));
    }

    @Test
    @DisplayName("3. Fichier trop volumineux refusé (400)")
    void uploadTooBig() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024]; // 6 Mo > 5 Mo
        mvc.perform(multipart(BASE)
                        .file(file("gros.pdf", big))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("volumineux")));
    }

    @Test
    @DisplayName("4. Fichier vide refusé (400)")
    void uploadEmpty() throws Exception {
        mvc.perform(multipart(BASE)
                        .file(file("vide.pdf", new byte[0]))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("obligatoire")));
    }

    @Test
    @DisplayName("5. Liste + téléchargement du fichier déposé")
    void listAndDownload() throws Exception {
        String res = mvc.perform(multipart(BASE)
                        .file(file("doc.pdf", "hello".getBytes()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(om.readTree(res).get("id").asText());

        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(get(BASE + "/" + id + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("doc.pdf")))
                .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    @DisplayName("6. Corbeille : suppression puis restauration")
    void softDeleteRestore() throws Exception {
        String res = mvc.perform(multipart(BASE)
                        .file(file("del.pdf", "x".getBytes()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(om.readTree(res).get("id").asText());

        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        mvc.perform(get(BASE + "/trashed")).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(get(BASE)).andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }
}
