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

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ipt.ged.support.Comptes;
import org.springframework.security.test.context.support.WithUserDetails;

/**
 * Chaîne d'OCRisation : lecture de la couche texte des PDF natifs, déduction de
 * valeurs d'index depuis le contenu, et repli documenté quand aucun moteur ne
 * peut lire le document.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// L'API est fermee par defaut : chaque appel doit porter une identite reelle.
// L'API est fermee par defaut : les tests s'authentifient avec le compte unique.
@WithUserDetails(Comptes.ADMIN)
class OcrApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private OcrService ocr;
    @Autowired private ExtracteurValeurs extracteurValeurs;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private long typeId;

    @BeforeEach
    void setup() {
        IndexField date  = index("O-DATE", "Date d'émission", IndexFieldType.DATE, null);
        IndexField fourn = index("O-FOURN", "Fournisseur", IndexFieldType.TEXTE, null);
        IndexField prio  = index("O-PRIO", "Priorité", IndexFieldType.LISTE, "Basse,Normale,Haute");

        PlanIndexation plan = new PlanIndexation("PL-OCR", "Fiche Facture");
        plan.setSeparateur("_");
        plan.getIndices().addAll(List.of(date, fourn, prio));
        planRepository.save(plan);

        Employe e = employeRepository.findById(1L).orElseThrow();
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

    private long depose(String nomFichier, byte[] contenu) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nomFichier, "application/pdf", contenu))
                        .param("name", "Document").param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(res).get("id").asLong();
    }

    @Test
    @DisplayName("1. La couche texte d'un PDF natif est lue, sans OCR")
    void coucheTexteLue() throws Exception {
        long doc = depose("scan0001.pdf", pdfAvecTexte("Fournisseur : ACME Distribution", "Montant : 1500"));

        mvc.perform(get("/api/v1/ocr/documents/" + doc + "/texte"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance", is("COUCHE_TEXTE")))
                .andExpect(jsonPath("$.texte", containsString("ACME Distribution")))
                .andExpect(jsonPath("$.nbPages", is(1)));
    }

    @Test
    @DisplayName("2. Un PDF sans couche texte est signalé comme nécessitant un OCR")
    void pdfSansTexte() throws Exception {
        // PDF valide mais vide : c'est le cas d'un scan
        byte[] vide = pdfAvecTexte();
        long doc = depose("scan-vierge.pdf", vide);

        mvc.perform(get("/api/v1/ocr/documents/" + doc + "/texte"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance", is("AUCUNE")))
                .andExpect(jsonPath("$.detail", not(emptyOrNullString())));
    }

    @Test
    @DisplayName("3. Le diagnostic expose les extracteurs et leur disponibilité")
    void diagnostic() throws Exception {
        mvc.perform(get("/api/v1/ocr/diagnostic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nom", containsString("PDF natif")))
                .andExpect(jsonPath("$[0].disponible", is(true)))
                .andExpect(jsonPath("$[?(@.nom =~ /.*Tesseract.*/)]", hasSize(1)));
    }

    @Test
    @DisplayName("4. Les valeurs se déduisent du texte selon le type de l'index")
    void deductionDepuisTexte() {
        String texte = """
            FACTURE
            Fournisseur : ACME Distribution
            Date d'émission : 15/01/2026
            Priorité : Haute
            """;
        IndexField fourn = indexRepository.findAll().stream()
                .filter(f -> "O-FOURN".equals(f.getCode())).findFirst().orElseThrow();
        IndexField date = indexRepository.findAll().stream()
                .filter(f -> "O-DATE".equals(f.getCode())).findFirst().orElseThrow();
        IndexField prio = indexRepository.findAll().stream()
                .filter(f -> "O-PRIO".equals(f.getCode())).findFirst().orElseThrow();

        assertEquals("ACME Distribution", extracteurValeurs.deduire(fourn, texte, List.of()));
        // date française normalisée en ISO
        assertEquals("2026-01-15", extracteurValeurs.deduire(date, texte, List.of()));
        assertEquals("Haute", extracteurValeurs.deduire(prio, texte, List.of("Basse", "Normale", "Haute")));
    }

    @Test
    @DisplayName("5. Un fichier au nom muet est indexé depuis son contenu")
    void indexeDepuisContenu() throws Exception {
        // « scan0001.pdf » ne suit aucune charte : seul le contenu peut renseigner les index
        long doc = depose("scan0001.pdf", pdfAvecTexte(
                "Fournisseur : ACME Distribution",
                "Date d'emission : 15/01/2026",
                "Priorite : Haute"));

        mvc.perform(get("/api/v1/indexation/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenanceTexte", is("COUCHE_TEXTE")))
                // la date et la priorité viennent du contenu, pas du nom de fichier
                .andExpect(jsonPath("$.propositions[?(@.code=='O-DATE')].source", contains("CONTENU")))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-DATE')].valeurProposee", contains("2026-01-15")))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-PRIO')].source", contains("CONTENU")))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-PRIO')].valeurProposee", contains("Haute")));
    }

    @Test
    @DisplayName("6. Le contenu n'est pas sollicité quand le nom de fichier suffit")
    void contenuNonSollicite() throws Exception {
        long doc = depose("2026-01-15_ACME Distribution_Haute.pdf",
                pdfAvecTexte("Fournisseur : Autre Societe"));

        String res = mvc.perform(get("/api/v1/indexation/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nbReconnus", is(3)))
                .andExpect(jsonPath("$.propositions[*].source", everyItem(is("NOM_FICHIER"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // le contenu aurait donné « Autre Societe » : il n'a pas été lu
        assertTrue(res.contains("non sollicité"), "le contenu ne doit pas être lu inutilement");
        assertFalse(res.contains("Autre Societe"));
    }

    @Test
    @DisplayName("7. Une valeur venue du contenu subit le même contrôle de type")
    void contenuControle() throws Exception {
        // « Extreme » n'appartient pas à la liste : la proposition doit être écartée
        long doc = depose("scan0002.pdf", pdfAvecTexte("Priorite : Extreme"));

        mvc.perform(get("/api/v1/indexation/documents/" + doc + "/analyse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propositions[?(@.code=='O-PRIO')].reconnue", contains(false)))
                .andExpect(jsonPath("$.propositions[?(@.code=='O-PRIO')].valeurProposee",
                        everyItem(nullValue())));
    }

    @Test
    @DisplayName("8. Tesseract absent n'empêche pas la chaîne de répondre")
    void tesseractAbsentSansCasse() {
        List<OcrService.EtatExtracteur> etats = ocr.diagnostic();
        assertTrue(etats.stream().anyMatch(e -> e.nom().contains("PDF natif") && e.disponible()));
        // Le poste de développement n'a pas Tesseract : l'étage est ignoré, pas fatal
        assertTrue(etats.stream().anyMatch(e -> e.nom().contains("Tesseract")));
        assertEquals(10, etats.get(0).priorite(), "la couche texte doit être tentée en premier");
    }
}
