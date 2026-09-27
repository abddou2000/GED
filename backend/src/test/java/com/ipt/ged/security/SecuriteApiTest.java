package com.ipt.ged.security;

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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

/**
 * Tests de non-régression des correctifs de sécurité qui subsistent.
 *
 * <p>Chacun de ces tests <b>échoue</b> si l'on retire le correctif qu'il vise :
 * ils ne décrivent pas un comportement souhaitable en général, ils épinglent une
 * décision de sécurité précise.
 *
 * <h2>Ce que ces tests ne couvrent plus</h2>
 * <p>L'application n'a qu'un seul utilisateur : la couche d'AUTORISATION (rôles,
 * permissions nommées, {@code @PreAuthorize}) a été retirée. Les tests qui
 * vérifiaient qu'un appelant authentifié mais non administrateur recevait 403
 * ont donc été supprimés plutôt que retournés en « tout le monde passe » : un
 * test qui n'épingle plus aucune décision entretient l'illusion d'une
 * couverture.
 *
 * <p>Ce qui reste testé est l'AUTHENTIFICATION — un appelant <b>non connecté</b>
 * reçoit 401 — et la TRAÇABILITÉ : l'identité de l'acteur vient du jeton, jamais
 * de la requête.
 *
 * <p>Aucune annotation d'authentification au niveau de la classe, volontairement :
 * plusieurs tests ont besoin de partir d'un état non authentifié, et c'est
 * l'objet même du premier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecuriteApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private UserDetailsService utilisateurs;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private UUID typeId;

    /** Incarne une identité réelle : principal de type {@code UtilisateurConnecte}. */
    private RequestPostProcessor enTantQue(String identifiant) {
        return user(utilisateurs.loadUserByUsername(identifiant));
    }

    @BeforeEach
    void setup() {
        Employe sara = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();

        WorkflowGed wf = new WorkflowGed("Circuit sécurité");
        wf.addStep(new WorkflowStep(sara, "Contrôle", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Securite", "WS-SEC");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(sara);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-SEC", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    /* ==================================================================
       Correctif : l'API est fermée par défaut (anyRequest().authenticated())
       ================================================================== */

    @Test
    @DisplayName("A. Sans authentification, une route protégée répond 401 (et pas une page de login)")
    void sansJetonCest401() throws Exception {
        // Lecture
        mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/signatures/pending")).andExpect(status().isUnauthorized());
        // Écriture
        mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"tag\":\"X\",\"couleur\":\"#000000\"}"))
                .andExpect(status().isUnauthorized());
        // Le profil courant, qui divulguait auparavant la fiche du premier compte
        mvc.perform(get("/api/v1/employes/profil")).andExpect(status().isUnauthorized());
        // La fiche d'un employé désigné par son identifiant
        mvc.perform(get("/api/v1/employes/" + Comptes.idAdmin(employeRepository) + "/profil"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("B. La connexion reste ouverte : identifiants faux → 401, pas 403 ni 500")
    void connexionOuverteMaisControlee() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr("10.99.0.1"); return r; })
                        .content("{\"identifiant\":\"inconnu\",\"motDePasse\":\"faux\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ==================================================================
       Correctif : l'identité vient du jeton
       ================================================================== */

    @Test
    @DisplayName("C. Usurpation de décision : l'employé B ne peut pas décider pour le validateur A")
    void usurpationDeSignatureImpossible() throws Exception {
        // Sara dépose : le circuit crée une étape assignée à Sara.
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "sec.pdf", "application/pdf", "x".getBytes()))
                        .with(enTantQue(Comptes.ADMIN))
                        .param("name", "Doc sécurité")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID docId = UUID.fromString(om.readTree(res).get("id").asText());

        String circuit = mvc.perform(get("/api/v1/workflow/documents/" + docId + "/circuits")
                        .with(enTantQue(Comptes.ADMIN)))
                .andReturn().getResponse().getContentAsString();
        JsonNode c = om.readTree(circuit).get(0);
        UUID circuitId = UUID.fromString(c.get("id").asText());
        UUID validateurId = UUID.fromString(c.get("validateurs").get(0).get("id").asText());

        // Karim tente de décider à la place de Sara (workflow §12.8) : sans
        // désigner de validateur, en désignant celui de Sara, avec l'employé de
        // Sara dans le corps. Aucune forme n'aboutit — l'acteur vient du jeton.
        String url = "/api/v1/workflow/circuits/" + circuitId + "/decisions";
        mvc.perform(post(url).with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{\"decision\":\"VALIDE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAS_VALIDATEUR"));

        mvc.perform(post(url).with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"VALIDE\",\"validateurId\":\"" + validateurId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAS_VALIDATEUR"));

        mvc.perform(post(url).with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"REFUSE\",\"motif\":\"tentative\",\"employeId\":\""
                                + Comptes.idAdmin(employeRepository) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAS_VALIDATEUR"));

        // La validatrice légitime, elle, passe.
        mvc.perform(post(url).with(enTantQue(Comptes.ADMIN))
                        .contentType(APPLICATION_JSON).content("{\"decision\":\"VALIDE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statut").value("VALIDE"));
    }

    @Test
    @DisplayName("D. /employes/profil renvoie le porteur du jeton, plus le premier compte de la base")
    void profilEstCeluiDuJeton() throws Exception {
        mvc.perform(get("/api/v1/employes/profil").with(enTantQue(Comptes.SECOND_ACTEUR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Comptes.idSecondActeur(employeRepository).toString()))
                .andExpect(jsonPath("$.fullName").value("Karim El Fassi"));

        mvc.perform(get("/api/v1/employes/profil").with(enTantQue(Comptes.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Comptes.idAdmin(employeRepository).toString()))
                .andExpect(jsonPath("$.fullName").value("Sara Bennani"));
    }
}
