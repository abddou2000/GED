package com.ipt.ged.indexation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.index.IndexRepository;
import com.ipt.ged.planindexation.CharteNommage;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.support.Comptes;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Points 2, 3, 8 et 11 de l'audit — l'écriture des index acceptait à peu près
 * tout et renommait le document dans la foulée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class IndexationControlesApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private PlanIndexationRepository planRepository;
    @Autowired private IndexRepository indexRepository;

    private static final String BASE = "/api/v1/indexation";

    /** Type dont le plan porte DEUX index obligatoires + une charte à jetons système. */
    private UUID typeId;
    private UUID idFournisseur, idMontant, idFacultatif;
    /** Index existant mais absent du plan de ce type. */
    private UUID idHorsPlan;

    @BeforeEach
    void setup() {
        IndexField fournisseur = index("C-FOURN", "Fournisseur", IndexFieldType.TEXTE, true);
        IndexField montant = index("C-MONT", "Montant", IndexFieldType.NOMBRE, true);
        IndexField facultatif = index("C-NOTE", "Note", IndexFieldType.TEXTE, false);
        IndexField horsPlan = index("C-AUTRE", "Index d'un autre plan", IndexFieldType.TEXTE, false);
        idFournisseur = fournisseur.getId();
        idMontant = montant.getId();
        idFacultatif = facultatif.getId();
        idHorsPlan = horsPlan.getId();

        PlanIndexation plan = new PlanIndexation("PL-CTRL", "Plan contrôlé");
        plan.getIndices().addAll(List.of(fournisseur, montant, facultatif));
        // Charte à jetons système : c'est elle qui produisait « 260811_105301_?_? ».
        plan.setCharteNommage(CharteNommage.serialiser(
                List.of("date", "houres", String.valueOf(idFournisseur), String.valueOf(idMontant)),
                "_", false));
        planRepository.save(plan);

        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF contrôles"));

        WorkSpace w = new WorkSpace("Contrôles", "WS-CTRL");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);

        TypeDocument type = new TypeDocument("TD-CTRL", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setPlanIndexation(plan);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private IndexField index(String code, String nom, IndexFieldType type, boolean obligatoire) {
        IndexField f = new IndexField(code, nom);
        f.setFieldType(type);
        f.setObligatoire(obligatoire);
        f.setIndexePourRecherche(true);
        return indexRepository.save(f);
    }

    private UUID deposer(String nom) throws Exception {
        String reponse = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("name", nom)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(reponse).get("id").asText());
    }

    private String nomDu(UUID documentId) throws Exception {
        String reponse = mvc.perform(get("/api/v1/documents/" + documentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(reponse).get("name").asText();
    }

    @Test
    @DisplayName("2a. Un corps vide ne renomme plus rien : 400 tant qu'un index obligatoire manque")
    void corpsVideRefuse() throws Exception {
        UUID doc = deposer("Facture intacte");

        /* Avant correction : 200, et le nom du document devenait
           « 260811_105301_?_? » — les seuls jetons système, suivis d'un « ? »
           par index non résolu. Le contrôle « obligatoire » vivait dans la
           boucle sur les lignes REÇUES : un corps vide ne bouclait sur rien. */
        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("obligatoire")));

        org.junit.jupiter.api.Assertions.assertEquals("Facture intacte", nomDu(doc),
                "le nom du document a été détruit par une requête refusée");
    }

    @Test
    @DisplayName("2b. Envoyer le seul index facultatif ne dispense pas des index obligatoires (400)")
    void obligatoiresEsquivesRefuses() throws Exception {
        UUID doc = deposer("Facture esquive");

        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":[{\"indexFieldId\":\"" + idFacultatif + "\",\"valeur\":\"une note\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("obligatoire")));

        // Rien n'a été écrit : le refus est complet, pas partiel.
        mvc.perform(get(BASE + "/documents/" + doc)).andExpect(jsonPath("$", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertEquals("Facture esquive", nomDu(doc));
    }

    @Test
    @DisplayName("2c. Un document verrouillé refuse l'indexation, comme il refuse déjà PUT /documents/{id}")
    void documentVerrouille() throws Exception {
        UUID doc = deposer("Facture verrouillée");
        mvc.perform(patch("/api/v1/documents/" + doc + "/verrou").param("verrouille", "true"))
                .andExpect(status().isOk());

        /* Avant correction : 200 et renommage. Le verrou n'était contrôlé que
           sur PUT /documents/{id} — l'indexation offrait une seconde porte,
           qui renomme pourtant elle aussi le document. */
        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":["
                                + "{\"indexFieldId\":\"" + idFournisseur + "\",\"valeur\":\"ACME\"},"
                                + "{\"indexFieldId\":\"" + idMontant + "\",\"valeur\":\"100\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("errouill")));

        org.junit.jupiter.api.Assertions.assertEquals("Facture verrouillée", nomDu(doc));
    }

    @Test
    @DisplayName("3. Un index étranger au plan du type est refusé (400) et n'atteint jamais la recherche")
    void indexHorsPlanRefuse() throws Exception {
        UUID doc = deposer("Facture hors plan");

        /* Avant correction : `indexRepository.findById` sans contrôle
           d'appartenance. La valeur était stockée, invisible dans le formulaire
           (qui n'affiche que le plan), mais bien renvoyée dans les résultats
           de recherche. */
        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":["
                                + "{\"indexFieldId\":\"" + idFournisseur + "\",\"valeur\":\"ACME\"},"
                                + "{\"indexFieldId\":\"" + idMontant + "\",\"valeur\":\"100\"},"
                                + "{\"indexFieldId\":\"" + idHorsPlan + "\",\"valeur\":\"contrebande\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("plan d'indexation")));

        mvc.perform(get(BASE + "/documents/" + doc)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post(BASE + "/recherche").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("contrebande"))));
    }

    @Test
    @DisplayName("8. POST /indexation/apercu applique les contraintes du dépôt : format et taille")
    void apercuContraint() throws Exception {
        // Format : le type n'autorise que « pdf ».
        mvc.perform(multipart(BASE + "/apercu")
                        .file(new MockMultipartFile("file", "charge.exe", "application/octet-stream", "MZ".getBytes()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("non autorisé")));

        // Taille : 6 Mo pour un plafond de 5.
        mvc.perform(multipart(BASE + "/apercu")
                        .file(new MockMultipartFile("file", "enorme.pdf", "application/pdf", new byte[6 * 1024 * 1024]))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail", containsString("volumineux")));

        // Type réel : un « .pdf » qui n'est que du texte est refusé comme au dépôt.
        mvc.perform(multipart(BASE + "/apercu")
                        .file(new MockMultipartFile("file", "faux.pdf", "application/pdf", "pas un pdf".getBytes()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isUnsupportedMediaType());

        // Un fichier conforme passe toujours : la route reste utilisable.
        mvc.perform(multipart(BASE + "/apercu")
                        .file(new MockMultipartFile("file", "ACME_100.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("11. Le nom composé n'emporte jamais de « ? » : un jeton non résolu est omis")
    void nomSansPointInterrogation() throws Exception {
        UUID doc = deposer("Facture partielle");

        // Les deux obligatoires sont fournis, le facultatif reste vide : la
        // charte compte pourtant quatre jetons (date, heure, fournisseur, montant).
        mvc.perform(put(BASE + "/documents/" + doc).contentType(APPLICATION_JSON)
                        .content("{\"valeurs\":["
                                + "{\"indexFieldId\":\"" + idFournisseur + "\",\"valeur\":\"ACME\"},"
                                + "{\"indexFieldId\":\"" + idMontant + "\",\"valeur\":\"100\"},"
                                + "{\"indexFieldId\":\"" + idFacultatif + "\",\"valeur\":null}]}"))
                .andExpect(status().isOk());

        String nom = nomDu(doc);
        org.junit.jupiter.api.Assertions.assertFalse(nom.contains("?"),
                "un « ? » littéral s'est glissé dans le nom du document : " + nom);
        org.junit.jupiter.api.Assertions.assertTrue(nom.endsWith("ACME_100"), nom);
    }
}
