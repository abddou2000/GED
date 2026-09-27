package com.ipt.ged.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.common.Tri;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Robustesse du dépôt et du téléchargement — points 4, 7, 10, 11 et 12 de
 * l'audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class DepotRobustesseApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private UploadDocumentRepository documentRepository;
    @Autowired private DocumentService documentService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${ged.fichiers.racine}") private String racineStockage;

    private static final String BASE = "/api/v1/documents";
    private UUID typeId, workspaceId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF robustesse"));

        WorkSpace w = new WorkSpace("Robustesse", "WS-ROB-" + UUID.randomUUID().toString().substring(0, 8));
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();

        TypeDocument type = new TypeDocument("TD-ROB-" + UUID.randomUUID().toString().substring(0, 8), "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private MockMultipartFile fichier(String nom) {
        return new MockMultipartFile("file", nom, "application/pdf", com.ipt.ged.support.Pdfs.pdf());
    }

    /** Nombre de fichiers chiffrés publiés dans le référentiel ({@code aa/bb/<uuid>.enc}). */
    private long fichiersSurLeDisque() {
        Path racine = Paths.get(racineStockage).toAbsolutePath().normalize();
        if (!Files.isDirectory(racine)) return 0;
        try (Stream<Path> s = Files.walk(racine)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".enc")).count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("4a. Une date d'expiration invalide est refusée AVANT toute écriture disque : 400 et zéro fichier orphelin")
    void aucunFichierOrphelin() throws Exception {
        long avant = fichiersSurLeDisque();

        /* Avant correction : storage.store() précédait LocalDate.parse().
           L'analyse échouait, la transaction annulait la base — pas le disque —
           et il restait 1 fichier pour 0 document, invisible de l'application.
           La DateTimeParseException sortait par ailleurs en 500. */
        mvc.perform(multipart(BASE)
                        .file(fichier("facture.pdf"))
                        .param("name", "Facture datée")
                        .param("typeDocumentId", String.valueOf(typeId))
                        .param("expirationDate", "2026-13-45"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("AAAA-MM-JJ")));

        assertEquals(avant, fichiersSurLeDisque(), "un fichier orphelin est resté sur le disque");
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("4b. Compensation : si la transaction du dépôt est annulée, le fichier chiffré et sa clé sont détruits")
    void compensationSiAnnulation() {
        long fichiersAvant = fichiersSurLeDisque();
        Long clesAvant = jdbc.queryForObject("SELECT count(*) FROM cle_fichier", Long.class);

        // Transaction propre (REQUIRES_NEW), annulée après un dépôt réussi : le
        // fichier a bien été écrit, puis la base a tout rejeté.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(statut -> {
            documentService.upload(fichier("annule.pdf"), "Annulé", typeId, null, null, null);
            assertEquals(fichiersAvant + 1, fichiersSurLeDisque(), "le fichier chiffré est écrit avant la validation");
            statut.setRollbackOnly();
        });

        assertEquals(fichiersAvant, fichiersSurLeDisque(), "fichier chiffré orphelin après annulation");
        assertEquals(clesAvant, jdbc.queryForObject("SELECT count(*) FROM cle_fichier", Long.class));
    }

    @Test
    @DisplayName("7. Un nom de plus de 255 caractères est refusé en 400, en nommant le champ et la limite")
    void nomTropLong() throws Exception {
        String trop = "N".repeat(300);

        // Au dépôt
        mvc.perform(multipart(BASE)
                        .file(fichier("UUID.pdf"))
                        .param("name", trop)
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("nom du document")))
                .andExpect(jsonPath("$.message", containsString("255")));

        // Et à la modification de la fiche
        String reponse = mvc.perform(multipart(BASE)
                        .file(fichier("ok.pdf"))
                        .param("name", "Nom correct")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(om.readTree(reponse).get("id").asText());

        mvc.perform(put(BASE + "/" + id).contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("name", trop))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("255")));

        // Un nom de groupe trop UUID est refusé de la même façon (champ nommé
        // dans « errors »), au lieu du 500 nu que remontait la base.
        mvc.perform(post("/api/v1/access-groups").contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("code", "G-LONG", "name", trop))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name", containsString("255")));
    }

    @Test
    @DisplayName("10. Déposer dans un type de document en corbeille est refusé (400)")
    void typeEnCorbeille() throws Exception {
        TypeDocument type = typeRepository.findById(typeId).orElseThrow();
        type.mettreEnCorbeille(null);
        typeRepository.save(type);

        mvc.perform(multipart(BASE)
                        .file(fichier("orpheline.pdf"))
                        .param("name", "Pièce orpheline")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("corbeille")));
    }

    @Test
    @DisplayName("11. Content-Disposition : nom accentué encodé RFC 5987, guillemets neutralisés, en-tête jamais perdu")
    void enTeteTelechargement() throws Exception {
        String reponse = mvc.perform(multipart(BASE)
                        .file(fichier("rapport.pdf"))
                        .param("name", "Rapport \"été\" 2026")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(om.readTree(reponse).get("id").asText());

        /* Avant correction : « attachment; filename="Rapport "été" 2026.pdf" ».
           Le guillemet refermait la valeur, et le « é » — hors ISO-8859-1 en
           sortie — faisait purement et simplement disparaître l'en-tête. */
        String entete = mvc.perform(get(BASE + "/" + id + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Content-Disposition");

        assertNotNull(entete, "l'en-tête a disparu");
        assertTrue(entete.startsWith("attachment;"), entete);
        // Repli ASCII : plus aucun guillemet parasite ni caractère non ASCII.
        String repli = entete.substring(entete.indexOf("filename=\"") + 10, entete.indexOf("\";"));
        assertFalse(repli.contains("\""), "guillemet non neutralisé : " + entete);
        assertTrue(repli.chars().allMatch(c -> c >= 0x20 && c <= 0x7E), "caractère non ASCII : " + entete);
        // Forme étendue, seule à porter le nom exact.
        assertTrue(entete.contains("filename*=UTF-8''"), entete);
        assertTrue(entete.contains("%C3%A9"), "le « é » n'est pas encodé en UTF-8 : " + entete);
        // Et jamais de « ? » littéral, interdit dans un nom de fichier Windows.
        assertFalse(entete.contains("?"), entete);
    }

    @Test
    @DisplayName("12. Pagination : un numéro de page qui déborde donne 400, et la taille est plafonnée")
    void paginationBornee() throws Exception {
        // Avant correction : 500 « Page offset exceeds Integer.MAX_VALUE ».
        mvc.perform(get(BASE).param("page", "2000000000").param("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("hors limites")));

        mvc.perform(get(BASE).param("page", "-1"))
                .andExpect(status().isBadRequest());

        // Aucun plafond n'existait sur la taille : size=100000 ramenait la table.
        mvc.perform(get(BASE).param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(Tri.TAILLE_MAX)));
    }
}
