package com.ipt.ged.indexation;

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
 * Campagne de tests de l'indexation : critères dérivés des index, saisie et
 * contrôle des valeurs, puis recherche multi-critères et regroupement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class IndexationApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private static final String BASE = "/api/v1/indexation";

    private long typeId, docA, docB;
    private long idFournisseur, idDate, idMontant, idPriorite;

    @BeforeEach
    void setup() throws Exception {
        // --- Index : un par type, tous cherchables ; Fournisseur sert au groupage
        IndexField fournisseur = index("T-FOURN", "Fournisseur", IndexFieldType.TEXTE, null, true);
        IndexField date        = index("T-DATE", "Date d'émission", IndexFieldType.DATE, null, false);
        IndexField montant     = index("T-MONT", "Montant", IndexFieldType.NOMBRE, null, false);
        IndexField priorite    = index("T-PRIO", "Priorité", IndexFieldType.LISTE, "Basse,Normale,Haute", false);
        idFournisseur = fournisseur.getId(); idDate = date.getId();
        idMontant = montant.getId();         idPriorite = priorite.getId();

        PlanIndexation plan = new PlanIndexation();
        plan.setCode("PL-TEST");
        plan.setNomDuPlan("Plan facture");
        plan.setIndices(List.of(fournisseur, date, montant, priorite));
        planRepository.save(plan);

        Employe e = employeRepository.findById(1L).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF idx");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-IDX");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-IDX", "Facture");
        type.setDescription("Factures fournisseurs");
        type.setWorkspace(w);
        type.setPlanIndexation(plan);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();

        docA = depose("facture-a.pdf", "Facture A");
        docB = depose("facture-b.pdf", "Facture B");

        indexer(docA, "ACME Distribution", "2026-01-15", "1500", "Haute");
        indexer(docB, "Atlas Fournitures", "2026-03-20", "300",  "Basse");
    }

    private IndexField index(String code, String nom, IndexFieldType type, String valeurs, boolean groupage) {
        IndexField f = new IndexField(code, nom);
        f.setFieldType(type);
        f.setValeurs(valeurs);
        f.setIndexePourRecherche(true);
        f.setIndexDeGroupage(groupage);
        return indexRepository.save(f);
    }

    private long depose(String fichier, String nom) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", fichier, "application/pdf", "contenu".getBytes()))
                        .param("name", nom)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(res).get("id").asLong();
    }

    private void indexer(long doc, String fourn, String date, String montant, String prio) throws Exception {
        String corps = """
            {"valeurs":[
              {"indexFieldId":%d,"valeur":"%s"},
              {"indexFieldId":%d,"valeur":"%s"},
              {"indexFieldId":%d,"valeur":"%s"},
              {"indexFieldId":%d,"valeur":"%s"}]}
            """.formatted(idFournisseur, fourn, idDate, date, idMontant, montant, idPriorite, prio);
        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk());
    }

    private String recherche(String criteres) throws Exception {
        return mvc.perform(post(BASE + "/recherche").contentType(APPLICATION_JSON).content(criteres))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Identifiants des documents retournés par une recherche, tous groupes
     * confondus.
     *
     * <p>Ces tests cherchaient auparavant la chaîne « Facture A » dans la
     * réponse brute. Ce repère n'est plus fiable : en charte de nommage
     * automatique (le cas ici, {@code manuel = false}), l'enregistrement des
     * valeurs d'index <b>renomme</b> le document avec la référence composée —
     * « Facture A » devient « ACME Distribution_2026-01-15_1500_Haute ». Le
     * test comparait donc un libellé d'affichage, ce qui le rendait sensible à
     * une décision de nommage sans rapport avec la recherche. On compare
     * désormais les identifiants, qui, eux, désignent le document.
     */
    private java.util.List<Long> idsTrouves(String reponseJson) throws Exception {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode groupe : om.readTree(reponseJson)) {
            for (com.fasterxml.jackson.databind.JsonNode doc : groupe.get("documents")) {
                ids.add(doc.get("id").asLong());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("1. Les critères sont dérivés des index cochés « indexé pour recherche »")
    void criteresDerives() throws Exception {
        mvc.perform(get(BASE + "/criteres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='T-FOURN')].fieldType", contains("TEXTE")))
                .andExpect(jsonPath("$[?(@.code=='T-DATE')].fieldType", contains("DATE")))
                .andExpect(jsonPath("$[?(@.code=='T-MONT')].fieldType", contains("NOMBRE")))
                // un index LISTE expose ses options : le front en fait un menu déroulant
                .andExpect(jsonPath("$[?(@.code=='T-PRIO')].options[*]", hasItems("Basse", "Normale", "Haute")));
    }

    @Test
    @DisplayName("2. Les champs à saisir viennent du plan d'indexation du type")
    void champsDuPlan() throws Exception {
        mvc.perform(get(BASE + "/documents/" + docA + "/champs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].libelle", is("Fournisseur")));
    }

    @Test
    @DisplayName("3. Les valeurs saisies sont bien conservées")
    void valeursEnregistrees() throws Exception {
        mvc.perform(get(BASE + "/documents/" + docA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.code=='T-FOURN')].valeur", contains("ACME Distribution")));
    }

    @Test
    @DisplayName("4. Recherche TEXTE (contient) et LISTE (égal)")
    void rechercheTexteEtListe() throws Exception {
        String parFournisseur = recherche(
                "{\"criteres\":[{\"indexFieldId\":" + idFournisseur + ",\"valeur\":\"acme\"}]}");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(docA), idsTrouves(parFournisseur));

        String parPriorite = recherche(
                "{\"criteres\":[{\"indexFieldId\":" + idPriorite + ",\"valeur\":\"Basse\"}]}");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(docB), idsTrouves(parPriorite));
    }

    @Test
    @DisplayName("5. Recherche par plage de DATE et intervalle de NOMBRE")
    void recherchePlages() throws Exception {
        String parDate = recherche("{\"criteres\":[{\"indexFieldId\":" + idDate
                + ",\"de\":\"2026-01-01\",\"a\":\"2026-02-01\"}]}");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(docA), idsTrouves(parDate));

        // 300 < 1000 : seule la facture B doit sortir
        String parMontant = recherche("{\"criteres\":[{\"indexFieldId\":" + idMontant + ",\"a\":\"1000\"}]}");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(docB), idsTrouves(parMontant));
    }

    @Test
    @DisplayName("6. Plusieurs critères se combinent en ET")
    void criteresCombines() throws Exception {
        // Fournisseur ACME ET priorité Basse → aucun document ne satisfait les deux
        String vide = recherche("{\"criteres\":["
                + "{\"indexFieldId\":" + idFournisseur + ",\"valeur\":\"acme\"},"
                + "{\"indexFieldId\":" + idPriorite + ",\"valeur\":\"Basse\"}]}");
        org.junit.jupiter.api.Assertions.assertTrue(idsTrouves(vide).isEmpty());
    }

    @Test
    @DisplayName("7. Regroupement des résultats par index de groupage")
    void regroupement() throws Exception {
        mvc.perform(post(BASE + "/recherche").contentType(APPLICATION_JSON)
                        .content("{\"grouperPar\":" + idFournisseur + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].libelle", hasItems("ACME Distribution", "Atlas Fournitures")))
                .andExpect(jsonPath("$[0].total", is(1)));
    }

    @Test
    @DisplayName("8. Une valeur incompatible avec le type est refusée (400)")
    void valeurInvalide() throws Exception {
        mvc.perform(put(BASE + "/documents/" + docA).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":[{\"indexFieldId\":" + idMontant + ",\"valeur\":\"abc\"}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put(BASE + "/documents/" + docA).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":[{\"indexFieldId\":" + idPriorite + ",\"valeur\":\"Extrême\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
