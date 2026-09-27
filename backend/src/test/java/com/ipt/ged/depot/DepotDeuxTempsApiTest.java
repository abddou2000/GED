package com.ipt.ged.depot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Dépôt en deux temps (§12.11) et dépôt avec métadonnées en une opération
 * (§5.3). Sans transaction de test : chaque temps valide réellement la sienne,
 * c'est ce qui est vérifié (un échec du temps 2 laisse le temps 1 en base).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class DepotDeuxTempsApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private UUID typeAvecPlan, typeSansPlan;
    private String cFournisseur, cMontant, cDate, cNote;

    @BeforeEach
    void preparer() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        cFournisseur = "D2T-FOURN-" + s;
        cMontant = "D2T-MONT-" + s;
        cDate = "D2T-DATE-" + s;
        cNote = "D2T-NOTE-" + s;
        IndexField fournisseur = index(cFournisseur, "Fournisseur", IndexFieldType.TEXTE, true);
        IndexField montant = index(cMontant, "Montant", IndexFieldType.NOMBRE, true);
        IndexField date = index(cDate, "Date facture", IndexFieldType.DATE, false);
        IndexField note = index(cNote, "Note", IndexFieldType.TEXTE, false);
        PlanIndexation plan = new PlanIndexation("PL-D2T-" + s, "Plan deux temps " + s);
        plan.getIndices().addAll(List.of(fournisseur, montant, date, note));
        planRepository.save(plan);

        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF deux temps " + s));
        WorkSpace w = new WorkSpace("Deux temps " + s, "WS-D2T-" + s);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);
        typeAvecPlan = type("TD-D2T-P-" + s, w, plan);
        typeSansPlan = type("TD-D2T-S-" + s, w, null);
    }

    private IndexField index(String code, String nom, IndexFieldType nature, boolean obligatoire) {
        IndexField f = new IndexField(code, nom);
        f.setFieldType(nature);
        f.setObligatoire(obligatoire);
        f.setIndexePourRecherche(true);
        return indexRepository.save(f);
    }

    private UUID type(String code, WorkSpace w, PlanIndexation plan) {
        TypeDocument t = new TypeDocument(code, "Facture " + code);
        t.setDescription("desc");
        t.setWorkspace(w);
        t.setPlanIndexation(plan);
        t.setTypeAutorise("pdf");
        t.setTailleMaxMo(5);
        return typeRepository.save(t).getId();
    }

    private ResultActions deposer(UUID type, String metadonnees) throws Exception {
        MockMultipartHttpServletRequestBuilder r = multipart("/api/v1/documents");
        r.file(new MockMultipartFile("file", "facture.pdf", "application/pdf", Pdfs.pdf()));
        if (metadonnees != null) {
            r.file(new MockMultipartFile("metadonnees", "", "application/json",
                    metadonnees.getBytes(StandardCharsets.UTF_8)));
        }
        return mvc.perform(r.param("name", "facture").param("typeDocumentId", type.toString()));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int documentsDuType(UUID type) {
        return jdbc.queryForObject("SELECT count(*) FROM document WHERE type_document_id = ?", Integer.class, type);
    }

    private int clesFichiers() {
        return jdbc.queryForObject("SELECT count(*) FROM cle_fichier", Integer.class);
    }

    @Test
    @DisplayName("Type sans plan : 201, issue SANS_PLAN")
    void sansPlan() throws Exception {
        JsonNode r = json(deposer(typeSansPlan, null).andExpect(status().isCreated()));
        assertEquals("SANS_PLAN", r.get("statutIndexation").asText());
        assertTrue(r.get("id").asText().length() > 0);
    }

    @Test
    @DisplayName("Dépôt avec métadonnées valides en une opération : 201, INDEXE, valeurs enregistrées")
    void avecMetadonnees() throws Exception {
        String meta = "{\"" + cFournisseur + "\": \"ACME\", \"" + cMontant.toLowerCase() + "\": 1250.5, \""
                + cDate + "\": \"2026-09-01\"}";
        JsonNode r = json(deposer(typeAvecPlan, meta).andExpect(status().isCreated()));
        assertEquals("INDEXE", r.get("statutIndexation").asText());
        assertTrue(r.get("motifIndexation").isNull());
        UUID id = UUID.fromString(r.get("id").asText());
        String valeurs = mvc.perform(get("/api/v1/indexation/documents/" + id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(valeurs.contains("ACME") && valeurs.contains("1250.5") && valeurs.contains("2026-09-01"), valeurs);
        assertEquals("INDEXE", jdbc.queryForObject("SELECT statut_indexation FROM document WHERE id = ?",
                String.class, id));
    }

    @Test
    @DisplayName("Plan sans métadonnées : document reçu, issue A_INDEXER")
    void planSansMetadonnees() throws Exception {
        JsonNode r = json(deposer(typeAvecPlan, null).andExpect(status().isCreated()));
        assertEquals("A_INDEXER", r.get("statutIndexation").asText());
    }

    @Test
    @DisplayName("Métadonnées invalides : 400 METADONNEES_INVALIDES, rien d'écrit (ni document ni fichier)")
    void metadonneesInvalides() throws Exception {
        int docs = documentsDuType(typeAvecPlan), cles = clesFichiers();
        String meta = "{\"" + cMontant + "\": \"beaucoup\", \"INCONNU\": \"x\", \"" + cDate + "\": \"01/09/2026\"}";
        JsonNode r = json(deposer(typeAvecPlan, meta).andExpect(status().isBadRequest()));
        assertEquals(ErreurDepot.METADONNEES_INVALIDES, r.get("code").asText());
        String detail = r.get("message").asText();
        assertTrue(detail.contains("INCONNU") && detail.contains(cMontant) && detail.contains(cFournisseur)
                && detail.contains(cDate), detail);
        assertEquals(docs, documentsDuType(typeAvecPlan));
        assertEquals(cles, clesFichiers());

        deposer(typeAvecPlan, "[1, 2]").andExpect(status().isBadRequest());
        deposer(typeAvecPlan, "{pas du json").andExpect(status().isBadRequest());
        deposer(typeSansPlan, "{\"X\": \"y\"}").andExpect(status().isBadRequest());
        deposer(typeAvecPlan, "{\"" + cFournisseur + "\": {\"objet\": 1}, \"" + cMontant + "\": 1}")
                .andExpect(status().isBadRequest());
        assertEquals(docs, documentsDuType(typeAvecPlan));
    }

    @Test
    @DisplayName("Métadonnées de plus de 64 Ko : 413, rien d'écrit")
    void limite64Ko() throws Exception {
        int docs = documentsDuType(typeAvecPlan);
        String meta = "{\"" + cFournisseur + "\": \"ACME\", \"" + cMontant + "\": 1, \"" + cNote + "\": \""
                + "x".repeat(MetadonneesDepot.LIMITE_OCTETS) + "\"}";
        JsonNode r = json(deposer(typeAvecPlan, meta).andExpect(status().isPayloadTooLarge()));
        assertEquals(ErreurDepot.METADONNEES_TROP_VOLUMINEUSES, r.get("code").asText());
        assertEquals(docs, documentsDuType(typeAvecPlan));
    }

    @Test
    @DisplayName("Échec du temps 2 : le temps 1 reste acquis (A_INDEXER, fichier lisible), puis reprise → INDEXE")
    void echecTemps2PuisReprise() throws Exception {
        // Valeur valide pour le plan, mais le nom composé par la charte
        // automatique dépasse la colonne : l'écriture du temps 2 échoue.
        String meta = "{\"" + cFournisseur + "\": \"" + "A".repeat(300) + "\", \"" + cMontant + "\": 10}";
        JsonNode r = json(deposer(typeAvecPlan, meta).andExpect(status().isCreated()));
        assertEquals("A_INDEXER", r.get("statutIndexation").asText());
        assertFalse(r.get("motifIndexation").isNull());
        UUID id = UUID.fromString(r.get("id").asText());

        // Temps 1 validé : document, version et fichier chiffré conservés.
        assertEquals("A_INDEXER", jdbc.queryForObject("SELECT statut_indexation FROM document WHERE id = ?",
                String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM document_index_valeur WHERE document_id = ?",
                Integer.class, id));
        var asynchrone = mvc.perform(get("/api/v1/documents/" + id + "/download"))
                .andExpect(request().asyncStarted()).andReturn();
        byte[] contenu = mvc.perform(asyncDispatch(asynchrone))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(Pdfs.pdf(), contenu);

        // Reprise par l'écran d'indexation.
        String corps = om.writeValueAsString(java.util.Map.of("valeurs", List.of(
                java.util.Map.of("indexFieldId", idDe(cFournisseur), "valeur", "ACME"),
                java.util.Map.of("indexFieldId", idDe(cMontant), "valeur", "10"))));
        mvc.perform(put("/api/v1/indexation/documents/" + id).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk());
        JsonNode apres = json(mvc.perform(get("/api/v1/documents/" + id)).andExpect(status().isOk()));
        assertEquals("INDEXE", apres.get("statutIndexation").asText());
    }

    private UUID idDe(String code) {
        return jdbc.queryForObject("SELECT id FROM index_def WHERE code = ?", UUID.class, code);
    }
}
