package com.ipt.ged.indexation;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Indexation automatique : le nom du fichier alimente les index selon la charte
 * du plan, et la référence se compose toute seule — mais rien n'est enregistré
 * sans confirmation explicite de l'opérateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class IndexationAutomatiqueApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private static final String BASE = "/api/v1/indexation";

    private UUID typeId;
    private UUID idDate, idFourn, idPrio;

    @BeforeEach
    void setup() {
        IndexField date  = index("A-DATE", "Date d'émission", IndexFieldType.DATE, null);
        IndexField fourn = index("A-FOURN", "Fournisseur", IndexFieldType.TEXTE, null);
        IndexField prio  = index("A-PRIO", "Priorité", IndexFieldType.LISTE, "Basse,Normale,Haute");
        idDate = date.getId(); idFourn = fourn.getId(); idPrio = prio.getId();

        // Charte : Date_Fournisseur_Priorité, séparateur « _ », sans majuscules
        PlanIndexation plan = new PlanIndexation("PL-AUTO", "Fiche Facture");
        plan.setModeIndexation(true);
        plan.setSeparateur("_");
        plan.setMajuscule(false);
        // addAll sur la liste de l'entité : un List.of() immuable casserait un ré-enregistrement
        plan.getIndices().addAll(List.of(date, fourn, prio));
        planRepository.save(plan);

        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF auto");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-AUTO");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-AUTO", "Facture");
        type.setDescription("Factures fournisseurs");
        type.setWorkspace(w);
        type.setPlanIndexation(plan);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private IndexField index(String code, String nom, IndexFieldType type, String valeurs) {
        IndexField f = new IndexField(code, nom);
        f.setFieldType(type);
        f.setValeurs(valeurs);
        f.setIndexePourRecherche(true);
        return indexRepository.save(f);
    }

    /** Dépose un fichier et renvoie l'id du document créé. */
    private UUID depose(String fichier) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", fichier, "application/pdf", "x".getBytes()))
                        .param("name", "Facture")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    @Test
    @DisplayName("1. Un fichier nommé selon la charte remplit tous les index")
    void nomConforme() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf");

        mvc.perform(get(BASE + "/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nbReconnus", is(3)))
                .andExpect(jsonPath("$.nbAttendus", is(3)))
                .andExpect(jsonPath("$.avertissement").doesNotExist())
                .andExpect(jsonPath("$.propositions[0].valeurProposee", is("2026-01-15")))
                .andExpect(jsonPath("$.propositions[1].valeurProposee", is("ACME Distribution")))
                .andExpect(jsonPath("$.propositions[2].valeurProposee", is("Haute")))
                .andExpect(jsonPath("$.referenceProposee", is("2026-01-15_ACME Distribution_Haute")));
    }

    @Test
    @DisplayName("2. L'analyse n'écrit rien : sans confirmation, le document reste vierge")
    void analyseNEcritRien() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf");

        mvc.perform(get(BASE + "/documents/" + doc + "/analyse")).andExpect(status().isOk());

        // Aucune valeur enregistrée tant que l'opérateur n'a pas confirmé
        mvc.perform(get(BASE + "/documents/" + doc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("3. Après confirmation, les valeurs sont posées et la référence composée")
    void confirmationEcrit() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf");

        String corps = """
            {"valeurs":[
              {"indexFieldId":"%s","valeur":"2026-01-15"},
              {"indexFieldId":"%s","valeur":"ACME Distribution"},
              {"indexFieldId":"%s","valeur":"Haute"}]}
            """.formatted(idDate, idFourn, idPrio);

        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // La référence est visible sur le résultat de recherche
        mvc.perform(post(BASE + "/recherche").contentType(APPLICATION_JSON)
                        .content("{\"criteres\":[{\"indexFieldId\":\"" + idFourn + "\",\"valeur\":\"acme\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documents[0].reference", is("2026-01-15_ACME Distribution_Haute")));
    }

    @Test
    @DisplayName("4. Un segment incompatible est signalé, pas deviné")
    void segmentInvalide() throws Exception {
        // « pas-une-date » ne passe pas le contrôle DATE, « Extrême » n'est pas dans la liste
        UUID doc = depose("pas-une-date_ACME Distribution_Extreme.pdf");

        mvc.perform(get(BASE + "/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nbReconnus", is(1)))
                .andExpect(jsonPath("$.propositions[0].reconnue", is(false)))
                .andExpect(jsonPath("$.propositions[0].valeurProposee").doesNotExist())
                .andExpect(jsonPath("$.propositions[0].motif", containsString("date")))
                .andExpect(jsonPath("$.propositions[1].reconnue", is(true)))
                .andExpect(jsonPath("$.propositions[2].reconnue", is(false)))
                .andExpect(jsonPath("$.avertissement", containsString("2 champ(s)")));
    }

    @Test
    @DisplayName("5. Un fichier trop court laisse les champs manquants à la saisie")
    void nomIncomplet() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution.pdf");

        mvc.perform(get(BASE + "/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nbReconnus", is(2)))
                .andExpect(jsonPath("$.propositions[2].reconnue", is(false)))
                .andExpect(jsonPath("$.propositions[2].motif", containsString("position 3")));
    }

    @Test
    @DisplayName("6. La charte majuscules s'applique à la référence composée")
    void charteMajuscules() throws Exception {
        PlanIndexation plan = planRepository.findAll().stream()
                .filter(p -> "PL-AUTO".equals(p.getCode())).findFirst().orElseThrow();
        plan.setMajuscule(true);
        planRepository.save(plan);

        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf");
        mvc.perform(get(BASE + "/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.majuscule", is(true)))
                .andExpect(jsonPath("$.referenceProposee", is("2026-01-15_ACME DISTRIBUTION_HAUTE")));
    }

    @Test
    @DisplayName("7. Sans plan d'indexation, l'analyse le dit au lieu d'échouer")
    void sansPlan() throws Exception {
        TypeDocument sansPlan = new TypeDocument("TD-NOPLAN", "Note");
        sansPlan.setDescription("Notes internes");
        sansPlan.setWorkspace(workspaceRepository.findAll().get(0));
        sansPlan.setTypeAutorise("pdf");
        sansPlan.setTailleMaxMo(5);
        UUID id = typeRepository.save(sansPlan).getId();

        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "note.pdf", "application/pdf", "x".getBytes()))
                        .param("name", "Note").param("typeDocumentId", String.valueOf(id)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID doc = UUID.fromString(om.readTree(res).get("id").asText());

        mvc.perform(get(BASE + "/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propositions", hasSize(0)))
                .andExpect(jsonPath("$.avertissement", containsString("pas de plan")));
    }

    @Test
    @DisplayName("8. Effacer toutes les valeurs retire la référence")
    void referenceRetiree() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf");

        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON).content("""
            {"valeurs":[
              {"indexFieldId":"%s","valeur":"2026-01-15"},
              {"indexFieldId":"%s","valeur":"ACME Distribution"},
              {"indexFieldId":"%s","valeur":"Haute"}]}
            """.formatted(idDate, idFourn, idPrio))).andExpect(status().isOk());

        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON).content("""
            {"valeurs":[
              {"indexFieldId":"%s","valeur":null},
              {"indexFieldId":"%s","valeur":null},
              {"indexFieldId":"%s","valeur":null}]}
            """.formatted(idDate, idFourn, idPrio))).andExpect(status().isOk());

        mvc.perform(post(BASE + "/recherche").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documents[?(@.id=='" + doc + "')].reference",
                        everyItem(nullValue())));
    }
}
