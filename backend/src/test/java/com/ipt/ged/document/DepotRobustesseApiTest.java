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
    @Autowired private StorageService storage;
    @Autowired private UploadDocumentRepository documentRepository;

    @Value("${ged.storage.root}") private String racineStockage;

    private static final String BASE = "/api/v1/documents";
    private UUID typeId, workspaceId;

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF robustesse"));

        WorkSpace w = new WorkSpace("Robustesse", "WS-ROB");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();

        TypeDocument type = new TypeDocument("TD-ROB", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    private MockMultipartFile fichier(String nom) {
        return new MockMultipartFile("file", nom, "application/pdf", "contenu".getBytes(StandardCharsets.UTF_8));
    }

    /** Nombre de fichiers présents sur le disque pour le dossier de ce test. */
    private long fichiersSurLeDisque() {
        Path dossier = Paths.get(racineStockage).toAbsolutePath().normalize().resolve(String.valueOf(workspaceId));
        if (!Files.isDirectory(dossier)) return 0;
        try (Stream<Path> s = Files.list(dossier)) {
            return s.count();
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
                .andExpect(jsonPath("$.detail", containsString("AAAA-MM-JJ")));

        assertEquals(avant, fichiersSurLeDisque(), "un fichier orphelin est resté sur le disque");
    }

    @Test
    @DisplayName("4b. La compensation existe : StorageService sait supprimer un fichier, et refuse un chemin hors du stockage")
    void suppressionDeFichier() throws Exception {
        // Le dépôt réussi écrit un fichier ; il est bien là.
        String reponse = mvc.perform(multipart(BASE)
                        .file(fichier("a-supprimer.pdf"))
                        .param("name", "À supprimer")
                        .param("typeDocumentId", String.valueOf(typeId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(om.readTree(reponse).get("id").asText());

        String chemin = documentRepository.findById(id).orElseThrow().getFilePath();
        assertNotNull(chemin);
        assertTrue(Files.exists(storage.chemin(chemin)));

        // La suppression, seule compensation d'une transaction annulée, fonctionne…
        assertTrue(storage.supprimer(chemin));
        assertFalse(Files.exists(storage.chemin(chemin)));
        // …et est idempotente.
        assertFalse(storage.supprimer(chemin));
        // …et ne sort jamais de la racine de stockage.
        assertFalse(storage.supprimer("../../evade.txt"));
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
                .andExpect(jsonPath("$.detail", containsString("nom du document")))
                .andExpect(jsonPath("$.detail", containsString("255")));

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
                .andExpect(jsonPath("$.detail", containsString("255")));

        // Un nom de groupe trop UUID est refusé de la même façon (champ nommé
        // dans « errors »), au lieu du 500 nu que remontait la base.
        mvc.perform(post("/api/v1/access-groups").contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("code", "G-LONG", "name", trop))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erreurs.name", containsString("255")));
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
                .andExpect(jsonPath("$.detail", containsString("corbeille")));
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
                .andExpect(jsonPath("$.detail", containsString("hors limites")));

        mvc.perform(get(BASE).param("page", "-1"))
                .andExpect(status().isBadRequest());

        // Aucun plafond n'existait sur la taille : size=100000 ramenait la table.
        mvc.perform(get(BASE).param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(Tri.TAILLE_MAX)));
    }
}
