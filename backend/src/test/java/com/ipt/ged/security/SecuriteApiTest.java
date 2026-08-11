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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
    @Autowired private ServiceUtilisateurs utilisateurs;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private CompteUtilisateurRepository compteRepository;
    @Autowired private PasswordEncoder encodeur;

    private long typeId;

    /** Incarne un compte réel : principal de type {@code UtilisateurConnecte}. */
    private RequestPostProcessor enTantQue(String email) {
        return user(utilisateurs.loadUserByUsername(email));
    }

    @BeforeEach
    void setup() {
        Employe sara = employeRepository.findById(Comptes.ID_ADMIN).orElseThrow();

        /* Second acteur : l'amorçage n'ouvre qu'un compte (utilisateur unique).
           Le test d'usurpation a besoin de deux principaux distincts pour être
           autre chose qu'une tautologie. */
        Comptes.ouvrirCompte(compteRepository, employeRepository, encodeur,
                Comptes.ID_SECOND_ACTEUR, Comptes.SECOND_ACTEUR, "test-only-password");

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
        mvc.perform(get("/api/v1/employes/" + Comptes.ID_ADMIN + "/profil"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("B. La connexion reste ouverte : identifiants faux → 401, pas 403 ni 500")
    void connexionOuverteMaisControlee() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"inconnu@marchica.ma\",\"motDePasse\":\"faux\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ==================================================================
       Correctif : l'identité vient du jeton
       ================================================================== */

    @Test
    @DisplayName("C. Usurpation de signature : l'employé B ne peut pas approuver l'étape de l'employé A")
    void usurpationDeSignatureImpossible() throws Exception {
        // Sara dépose : le circuit crée une étape assignée à Sara.
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "sec.pdf", "application/pdf", "x".getBytes()))
                        .with(enTantQue(Comptes.ADMIN))
                        .param("name", "Doc sécurité")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long docId = om.readTree(res).get("id").asLong();

        String circuit = mvc.perform(get("/api/v1/signatures/document/" + docId).with(enTantQue(Comptes.ADMIN)))
                .andReturn().getResponse().getContentAsString();
        JsonNode etape = om.readTree(circuit).get(0);
        long sigId = etape.get("id").asLong();

        // Karim tente d'approuver. Les trois formes qui marchaient avant le
        // correctif sont rejouées : corps vide, employeId de la victime dans le
        // corps, employeId de la victime en paramètre de requête. Aucune ne doit
        // aboutir — l'identité comparée vient du principal, pas de la requête.
        mvc.perform(patch("/api/v1/signatures/" + sigId + "/approve").with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));

        mvc.perform(patch("/api/v1/signatures/" + sigId + "/approve").with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON)
                        .content("{\"employeId\":" + Comptes.ID_ADMIN + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));

        mvc.perform(patch("/api/v1/signatures/" + sigId + "/approve").with(enTantQue(Comptes.SECOND_ACTEUR))
                        .param("employeId", String.valueOf(Comptes.ID_ADMIN))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));

        // Le rejet suit la même règle.
        mvc.perform(patch("/api/v1/signatures/" + sigId + "/reject").with(enTantQue(Comptes.SECOND_ACTEUR))
                        .contentType(APPLICATION_JSON)
                        .content("{\"employeId\":" + Comptes.ID_ADMIN + ",\"motif\":\"tentative\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("assignée")));

        // L'assignée légitime, elle, passe.
        mvc.perform(patch("/api/v1/signatures/" + sigId + "/approve").with(enTantQue(Comptes.ADMIN))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    @Test
    @DisplayName("D. /employes/profil renvoie le porteur du jeton, plus le premier compte de la base")
    void profilEstCeluiDuJeton() throws Exception {
        mvc.perform(get("/api/v1/employes/profil").with(enTantQue(Comptes.SECOND_ACTEUR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) Comptes.ID_SECOND_ACTEUR))
                .andExpect(jsonPath("$.fullName").value("Karim El Fassi"));

        mvc.perform(get("/api/v1/employes/profil").with(enTantQue(Comptes.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) Comptes.ID_ADMIN))
                .andExpect(jsonPath("$.fullName").value("Sara Bennani"));
    }
}
