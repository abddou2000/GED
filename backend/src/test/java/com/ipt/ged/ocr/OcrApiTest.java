package com.ipt.ged.ocr;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Chaîne OCR vue de l'API après branchement (vague 2) : dépôt accepté en 202
 * avec un job en attente (§4.3.4, §12.11), texte « non interrogeable » tant que
 * le job n'est pas terminé, renvoi à l'OCR d'une version restaurée, et
 * cloisonnement §4.3.3 (le contenu n'alimente aucun index).
 */
@SpringBootTest(properties = {"ged.ocr.chaine.actif=true", "ged.ocr.chaine.workers=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class OcrApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private com.ipt.ged.ocr.file.OcrJobQueue file;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private UUID typeId;

    @BeforeEach
    void setup() {
        IndexField date  = index("O-DATE", "Date d'émission", IndexFieldType.DATE, null);
        IndexField fourn = index("O-FOURN", "Fournisseur", IndexFieldType.TEXTE, null);
        IndexField prio  = index("O-PRIO", "Priorité", IndexFieldType.LISTE, "Basse,Normale,Haute");

        PlanIndexation plan = new PlanIndexation("PL-OCR", "Fiche Facture");
        plan.setSeparateur("_");
        plan.getIndices().addAll(List.of(date, fourn, prio));
        planRepository.save(plan);

        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF ocr");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);

        WorkSpace w = new WorkSpace("Comptabilite", "WS-OCR");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-OCR", "Facture");
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

    /** Fabrique un vrai PDF portant une couche texte. */
    private static byte[] pdfAvecTexte(String... lignes) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream flux = new PDPageContentStream(doc, page)) {
                flux.beginText();
                flux.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                flux.newLineAtOffset(60, 720);
                for (String l : lignes) {
                    flux.showText(l);
                    flux.newLineAtOffset(0, -18);
                }
                flux.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private UUID depose(String nomFichier, byte[] contenu) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nomFichier, "application/pdf", contenu))
                        .param("name", "Document").param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statutOcr", is("EN_ATTENTE_OCR")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    private UUID versionCourante(UUID doc) throws Exception {
        String res = mvc.perform(get("/api/v1/documents/" + doc)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (var v : om.readTree(res).get("versions")) {
            if (v.get("principale").asBoolean()) return UUID.fromString(v.get("id").asText());
        }
        throw new AssertionError("aucune version courante");
    }

    @Test
    @DisplayName("1. Dépôt : 202 Accepted, job OCR en attente dans la même transaction, contenu non interrogeable")
    void depotAccepteEnAttenteOcr() throws Exception {
        UUID doc = depose("facture.pdf", pdfAvecTexte("Facture numero 2026-117"));
        UUID version = versionCourante(doc);
        assertEquals(com.ipt.ged.ocr.file.StatutOcr.EN_ATTENTE_OCR,
                file.statutsParVersion(List.of(version)).get(version));
        mvc.perform(get("/api/v1/ocr/documents/" + doc + "/texte"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statutOcr", is("EN_ATTENTE_OCR")))
                .andExpect(jsonPath("$.interrogeable", is(false)))
                .andExpect(jsonPath("$.versionId", is(version.toString())));
        mvc.perform(get("/api/v1/documents/" + doc))
                .andExpect(jsonPath("$.statutOcr", is("EN_ATTENTE_OCR")))
                .andExpect(jsonPath("$.versions[0].typeMime", is("application/pdf")))
                .andExpect(jsonPath("$.versions[0].empreinte", matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    @DisplayName("2. Nouvelle version : 202 et nouveau job ; restauration de l'ancienne : renvoyée à l'OCR")
    void versionsRenvoyeesALOcr() throws Exception {
        UUID doc = depose("contrat.pdf", pdfAvecTexte("Contrat v1"));
        UUID v1 = versionCourante(doc);
        mvc.perform(multipart("/api/v1/documents/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "contrat-v2.pdf", "application/pdf", pdfAvecTexte("Contrat v2"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statutOcr", is("EN_ATTENTE_OCR")));
        UUID v2 = versionCourante(doc);
        assertNotEquals(v1, v2);
        assertEquals(com.ipt.ged.ocr.file.StatutOcr.EN_ATTENTE_OCR, file.statutsParVersion(List.of(v2)).get(v2));
        mvc.perform(patch("/api/v1/documents/" + doc + "/versions/" + v1 + "/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statutOcr", is("EN_ATTENTE_OCR")));
        // Un seul job actif par version : v1 a été ré-enfilée (son premier job est encore en attente).
        assertEquals(com.ipt.ged.ocr.file.StatutOcr.EN_ATTENTE_OCR, file.statutsParVersion(List.of(v1)).get(v1));
    }

    @Test
    @DisplayName("3. État de la chaîne : active, modèles fra et ara installés, fra+ara par défaut")
    void etat() throws Exception {
        mvc.perform(get("/api/v1/ocr/etat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actif", is(true)))
                .andExpect(jsonPath("$.langueDefaut", is("fra+ara")))
                .andExpect(jsonPath("$.languesInstallees", hasItems("fra", "ara")));
    }

    @Test
    @DisplayName("4. Cloisonnement §4.3.3 : un fichier au nom muet n'est PAS indexé depuis son contenu")
    void contenuNAlimenteAucunIndex() throws Exception {
        // Le contenu porte des valeurs lisibles ; aucune ne doit remonter dans
        // les propositions d'index : l'OCR ne sert qu'à la recherche plein texte.
        UUID doc = depose("scan0001.pdf", pdfAvecTexte(
                "Fournisseur : ACME Distribution",
                "Date d'emission : 15/01/2026",
                "Priorite : Haute"));

        String res = mvc.perform(get("/api/v1/indexation/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceTexte", is("AUCUNE")))
                .andExpect(jsonPath("$.propositions[*].source", everyItem(not(is("CONTENU")))))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-DATE')].valeurProposee", everyItem(nullValue())))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-PRIO')].valeurProposee", everyItem(nullValue())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(res.contains("ACME Distribution"));
        assertFalse(res.contains("2026-01-15"));
    }

    @Test
    @DisplayName("5. Le nom de fichier reste la seule source des propositions")
    void nomDeFichierSeulesPropositions() throws Exception {
        UUID doc = depose("2026-01-15_ACME Distribution_Haute.pdf",
                pdfAvecTexte("Fournisseur : Autre Societe"));

        String res = mvc.perform(get("/api/v1/indexation/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nbReconnus", is(3)))
                .andExpect(jsonPath("$.propositions[*].source", everyItem(is("NOM_FICHIER"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(res.contains("§4.3.3"), "le motif du cloisonnement est donné à l'opérateur");
        assertFalse(res.contains("Autre Societe"));
    }
}
