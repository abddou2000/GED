package com.ipt.ged.fichier.previsualisation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.document.evenement.ApercuConsulte;
import com.ipt.ged.document.evenement.DocumentDepose;
import com.ipt.ged.document.evenement.DocumentTelecharge;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.fichier.controle.Echantillons;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Branchement E5 de bout en bout : dépôt réel (fichier chiffré), téléchargement
 * et aperçu (§6.1.6) déchiffrés à la volée, chacun avec son événement d'audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class ApercuTelechargementApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private ApplicationEvents evenements;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    @Value("${ged.fichiers.racine}") private String racine;

    private UUID typeId;

    @BeforeEach
    void preparer() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF apercu"));
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        WorkSpace w = new WorkSpace("Aperçus", "WS-AP-" + suffixe);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);
        TypeDocument type = new TypeDocument("TD-AP-" + suffixe, "Pièce");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTailleMaxMo(10);   // aucun format paramétré : liste blanche par défaut du §6.1.5
        typeId = typeRepository.save(type).getId();
    }

    private UUID[] deposer(String nom, byte[] contenu) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nom, "application/octet-stream", contenu))
                        .param("typeDocumentId", typeId.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var json = om.readTree(res);
        return new UUID[]{UUID.fromString(json.get("id").asText()),
                UUID.fromString(json.get("versions").get(0).get("id").asText())};
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Dépôt : fichier chiffré sur disque (aucun octet en clair), événement DocumentDepose avec empreinte")
    void depotChiffre() throws Exception {
        byte[] contenu = "Note confidentielle Marchica".getBytes(StandardCharsets.UTF_8);
        UUID[] ids = deposer("note.txt", contenu);
        DocumentDepose ev = evenements.stream(DocumentDepose.class).filter(e -> e.documentId().equals(ids[0]))
                .findFirst().orElseThrow();
        assertEquals("text/plain", ev.typeMime());
        assertEquals(64, ev.empreinte().length());
        assertEquals(contenu.length, ev.tailleOctets());
        assertNull(ev.statutOcr(), "chaîne OCR inactive dans ce profil : dépôt en 201");
        try (Stream<Path> s = Files.walk(Path.of(racine).toAbsolutePath().normalize())) {
            Path enc = s.filter(p -> p.getFileName().toString().equals(ev.cleFichierId() + ".enc"))
                    .findFirst().orElseThrow();
            assertFalse(new String(Files.readAllBytes(enc), StandardCharsets.ISO_8859_1).contains("confidentielle"));
        }
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Téléchargement déchiffré en flux, sans cache, avec événement DocumentTelecharge")
    void telechargement() throws Exception {
        byte[] pdf = Echantillons.pdf();
        UUID[] ids = deposer("rapport.pdf", pdf);
        MvcResult asynchrone = mvc.perform(get("/api/v1/documents/" + ids[0] + "/download"))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult r = mvc.perform(asyncDispatch(asynchrone))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(pdf.length)))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andReturn();
        assertArrayEquals(pdf, r.getResponse().getContentAsByteArray());
        assertEquals(1, evenements.stream(DocumentTelecharge.class)
                .filter(e -> e.documentId().equals(ids[0]) && e.versionId().equals(ids[1])).count());
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Aperçu PDF déchiffré à la volée, inline, avec événement ApercuConsulte distinct du téléchargement")
    void apercuPdf() throws Exception {
        byte[] pdf = Echantillons.pdf();
        UUID[] ids = deposer("plan.pdf", pdf);
        MvcResult asynchrone = mvc.perform(get("/api/v1/versions/{id}/apercu", ids[1]))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult r = mvc.perform(asyncDispatch(asynchrone))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Length", String.valueOf(pdf.length)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", startsWith("inline")))
                .andReturn();
        assertArrayEquals(pdf, r.getResponse().getContentAsByteArray());
        assertEquals(1, evenements.stream(ApercuConsulte.class)
                .filter(e -> e.documentId().equals(ids[0]) && e.versionId().equals(ids[1])).count());
        assertEquals(0, evenements.stream(DocumentTelecharge.class).count(), "l'aperçu n'est pas un téléchargement");
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Aperçu d'une image servi tel quel")
    void apercuImage() throws Exception {
        byte[] png = Echantillons.image("png");
        UUID[] ids = deposer("scan.png", png);
        MvcResult asynchrone = mvc.perform(get("/api/v1/versions/{id}/apercu", ids[1]))
                .andExpect(request().asyncStarted()).andReturn();
        assertArrayEquals(png, mvc.perform(asyncDispatch(asynchrone)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Aperçu bureautique sans LibreOffice sur le poste : 503 CONVERSION_INDISPONIBLE, aucun événement")
    void apercuBureautiqueSansLibreOffice() throws Exception {
        UUID[] ids = deposer("contrat.docx", Echantillons.docx());
        mvc.perform(get("/api/v1/versions/{id}/apercu", ids[1]))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONVERSION_INDISPONIBLE"));
        assertEquals(0, evenements.stream(ApercuConsulte.class).count());
    }

    @Test
    @WithUserDetails(Comptes.ADMIN)
    @DisplayName("Version inconnue : 404 FICHIER_INTROUVABLE")
    void versionInconnue() throws Exception {
        mvc.perform(get("/api/v1/versions/{id}/apercu", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FICHIER_INTROUVABLE"));
    }

    @Test
    @DisplayName("Anonyme : 401, rien n'est déchiffré")
    void anonyme() throws Exception {
        mvc.perform(get("/api/v1/versions/{id}/apercu", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
