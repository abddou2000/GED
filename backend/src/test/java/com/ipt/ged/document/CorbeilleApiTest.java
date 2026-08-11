package com.ipt.ged.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
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
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Points 5 et 6 de l'audit — <b>la corbeille n'opposait rien</b>, et supprimer
 * un document faussait le compteur du tableau de bord.
 *
 * <p>Règle retenue et vérifiée ici : <b>la fiche d'un élément supprimé reste
 * lisible, mais toute écriture y est refusée</b>. Lisible, parce que l'écran
 * « éléments supprimés » doit pouvoir décrire ce qu'il propose de restaurer ;
 * fermée à l'écriture, parce qu'une modification portée sur un document que
 * l'utilisateur croit supprimé ne sera relue par personne.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class CorbeilleApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private static final String BASE = "/api/v1/documents";
    private long typeId, workspaceId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.ID_ADMIN).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF corbeille");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Corbeille", "WS-CORB");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();

        TypeDocument type = new TypeDocument("TD-CORB", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private long deposer(String nom) throws Exception {
        String reponse = mvc.perform(multipart(BASE)
                        .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", "x".getBytes()))
                        .param("name", nom)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(reponse).get("id").asLong();
    }

    private long etapeDe(long documentId) throws Exception {
        String reponse = mvc.perform(get("/api/v1/signatures/document/" + documentId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(reponse).get(0).get("id").asLong();
    }

    private long compteurTableauDeBord() throws Exception {
        String reponse = mvc.perform(get("/api/v1/stats/overview"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(reponse).get("pendingSignatures").asLong();
    }

    private int tailleListeAttente() throws Exception {
        String reponse = mvc.perform(get("/api/v1/signatures/pending"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode liste = om.readTree(reponse);
        return liste.size();
    }

    @Test
    @DisplayName("5a. Document en corbeille : la fiche reste lisible, mais aucune écriture n'est acceptée")
    void ecrituresRefuseesSurDocumentSupprime() throws Exception {
        long id = deposer("Pièce supprimée");
        long etape = etapeDe(id);

        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());

        // Lecture : autorisée, et la réponse dit désormais que le document est
        // en corbeille — l'écran n'avait auparavant aucun moyen de le savoir.
        mvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        // Écritures : toutes refusées, sur chacune des portes ouvertes.
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renommé en douce\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("corbeille")));

        mvc.perform(patch(BASE + "/" + id + "/verrou").param("verrouille", "true"))
                .andExpect(status().isBadRequest());

        mvc.perform(multipart(BASE + "/" + id + "/versions")
                        .file(new MockMultipartFile("file", "v2.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/indexation/documents/" + id).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":[]}"))
                .andExpect(status().isBadRequest());

        // …y compris l'approbation de son circuit de signature.
        mvc.perform(patch("/api/v1/signatures/" + etape + "/approve")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("corbeille")));

        // La restauration reste possible, et rouvre l'écriture.
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renommé après restauration\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("5b. Espace de travail en corbeille : fiche lisible et signalée, écritures refusées")
    void ecrituresRefuseesSurEspaceSupprime() throws Exception {
        mvc.perform(delete("/api/v1/workspaces/" + workspaceId)).andExpect(status().isNoContent());

        // Avant correction, la fiche annonçait « Statut : Actif » sans rien dire
        // de la suppression : le champ `status` ne connaît qu'ACTIF/ARCHIVE.
        mvc.perform(get("/api/v1/workspaces/" + workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted", is(true)));

        mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/archive"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("corbeille")));

        mvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/parent")
                        .contentType(APPLICATION_JSON).content("{\"parentId\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6. Le compteur du tableau de bord et la liste « en attente » disent le même nombre, avant et après suppression")
    void compteurCoherentAvecLaListe() throws Exception {
        long reference = compteurTableauDeBord();
        assertEquals(tailleListeAttente(), reference, "compteur et liste divergeaient déjà");

        long id = deposer("Pièce à signer");
        assertEquals(reference + 1, compteurTableauDeBord());
        assertEquals(tailleListeAttente(), compteurTableauDeBord());

        /* Avant correction : `softDelete` ne touchait pas les WorkflowSignature
           et `stats/overview` comptait tous les PENDING de la base, corbeille
           comprise — d'où le tableau de bord annonçant 73 pour une liste de 41. */
        mvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());

        assertEquals(0, tailleListeAttente() - (int) compteurTableauDeBord(),
                "le compteur ne suit pas la liste");
        assertEquals(reference, compteurTableauDeBord(),
                "la pièce supprimée est encore comptée comme à signer");

        // La corbeille est réversible : restaurer rend le circuit tel quel.
        mvc.perform(patch(BASE + "/" + id + "/restore")).andExpect(status().isNoContent());
        assertEquals(reference + 1, compteurTableauDeBord());
        assertEquals(tailleListeAttente(), compteurTableauDeBord());
    }
}
