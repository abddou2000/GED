package com.ipt.ged.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.document.evenement.DocumentDepose;
import com.ipt.ged.document.evenement.DocumentRestaure;
import com.ipt.ged.document.evenement.DocumentSupprime;
import com.ipt.ged.document.evenement.EvenementDocument;
import com.ipt.ged.document.evenement.MetadonneesModifiees;
import com.ipt.ged.document.evenement.VerrouModifie;
import com.ipt.ged.document.evenement.VersionAjoutee;
import com.ipt.ged.document.evenement.VersionRestauree;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.ClientClamd;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.controle.FauxClamd;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.Pdfs;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Événements de domaine publiés par les opérations sur les documents (source
 * du journal d'audit), et antivirus réellement branché sur le dépôt : un
 * fichier infecté est refusé en 422 {@code FICHIER_INFECTE} sans rien écrire
 * (client clamd du dépôt contre un faux clamd).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@WithUserDetails(Comptes.ADMIN)
class EvenementsDocumentApiTest {

    private static final FauxClamd CLAMD;
    static {
        try {
            CLAMD = new FauxClamd();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Le dépôt passe par le vrai client clamd, branché sur le faux démon. */
    @TestConfiguration
    static class AntivirusSimule {
        @Bean
        @Primary
        AnalyseurAntivirus antivirusDeTest() {
            return new ClientClamd("127.0.0.1", CLAMD.port(), 1000, 5000, 8192);
        }
    }

    @AfterAll
    static void arreter() throws IOException {
        CLAMD.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private ApplicationEvents evenements;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;

    private UUID typeId, autreTypeId, employeId;

    @BeforeEach
    void preparer() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        employeId = e.getId();
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF evenements"));
        String s = UUID.randomUUID().toString().substring(0, 8);
        WorkSpace w = new WorkSpace("Audit", "WS-EV-" + s);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceRepository.save(w);
        typeId = type("TD-EV-" + s, w);
        autreTypeId = type("TD-EV2-" + s, w);
    }

    private UUID type(String code, WorkSpace w) {
        TypeDocument t = new TypeDocument(code, "Type " + code);
        t.setDescription("desc");
        t.setWorkspace(w);
        t.setTypeAutorise("pdf,txt");
        t.setTailleMaxMo(5);
        return typeRepository.save(t).getId();
    }

    private UUID deposer(String nom) throws Exception {
        String res = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", Pdfs.pdf(nom)))
                        .param("name", nom).param("typeDocumentId", typeId.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(om.readTree(res).get("id").asText());
    }

    private <T extends EvenementDocument> List<T> de(Class<T> type, UUID doc) {
        return evenements.stream(type).filter(e -> e.documentId().equals(doc)).toList();
    }

    @Test
    @DisplayName("Dépôt : DocumentDepose, acteur = employé du jeton, version et empreinte renseignées")
    void depot() throws Exception {
        UUID doc = deposer("depot");
        DocumentDepose e = de(DocumentDepose.class, doc).get(0);
        assertEquals("DOCUMENT_DEPOSE", e.type());
        assertEquals(employeId, e.acteur().employeId());
        assertNull(e.acteur().applicationId());
        assertNotNull(e.versionId());
        assertEquals(typeId, e.typeDocumentId());
        assertEquals("application/pdf", e.typeMime());
        assertEquals(64, e.empreinte().length());
    }

    @Test
    @DisplayName("Modification : MetadonneesModifiees avec avant/après des seuls champs changés")
    void modification() throws Exception {
        UUID doc = deposer("avant");
        mvc.perform(put("/api/v1/documents/" + doc).contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "après", "typeDocumentId", autreTypeId.toString()))))
                .andExpect(status().isOk());
        MetadonneesModifiees e = de(MetadonneesModifiees.class, doc).get(0);
        assertEquals(Map.of("nom", "avant", "typeDocumentId", typeId), e.avant());
        assertEquals(Map.of("nom", "après", "typeDocumentId", autreTypeId), e.apres());

        // Une modification sans changement effectif ne publie rien.
        mvc.perform(put("/api/v1/documents/" + doc).contentType(APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "après")))).andExpect(status().isOk());
        assertEquals(1, de(MetadonneesModifiees.class, doc).size());
    }

    @Test
    @DisplayName("Verrou, versions, corbeille : VerrouModifie, VersionAjoutee, VersionRestauree, DocumentSupprime, DocumentRestaure")
    void cycleDeVie() throws Exception {
        UUID doc = deposer("cycle");
        UUID v1 = de(DocumentDepose.class, doc).get(0).versionId();

        mvc.perform(multipart("/api/v1/documents/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "cycle-v2.pdf", "application/pdf", Pdfs.pdf("v2")))
                        .param("observation", "corrections"))
                .andExpect(status().isCreated());
        VersionAjoutee va = de(VersionAjoutee.class, doc).get(0);
        assertEquals(v1, va.versionPrecedenteId());
        assertEquals("corrections", va.observation());

        mvc.perform(patch("/api/v1/documents/" + doc + "/versions/" + v1 + "/default")).andExpect(status().isOk());
        VersionRestauree vr = de(VersionRestauree.class, doc).get(0);
        assertEquals(v1, vr.versionId());
        assertEquals(va.versionId(), vr.versionPrecedenteId());

        mvc.perform(patch("/api/v1/documents/" + doc + "/verrou").param("verrouille", "true")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/documents/" + doc + "/verrou").param("verrouille", "false")).andExpect(status().isOk());
        List<VerrouModifie> verrous = de(VerrouModifie.class, doc);
        assertEquals(List.of("DOCUMENT_VERROUILLE", "DOCUMENT_DEVERROUILLE"), verrous.stream().map(VerrouModifie::type).toList());

        mvc.perform(delete("/api/v1/documents/" + doc)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/documents/" + doc)).andExpect(status().isNoContent());
        mvc.perform(patch("/api/v1/documents/" + doc + "/restore")).andExpect(status().isNoContent());
        assertEquals(1, de(DocumentSupprime.class, doc).size(), "une mise en corbeille répétée n'est tracée qu'une fois");
        assertEquals(1, de(DocumentRestaure.class, doc).size());
    }

    @Test
    @DisplayName("Antivirus branché sur le dépôt : EICAR refusé en 422 FICHIER_INFECTE, rien d'écrit, aucun DocumentDepose")
    void fichierInfecte() throws Exception {
        byte[] eicar = FauxClamd.eicar().getBytes(StandardCharsets.US_ASCII);
        mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", eicar))
                        .param("typeDocumentId", typeId.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FICHIER_INFECTE"));
        assertEquals(0, evenements.stream(DocumentDepose.class).count());
        assertEquals(1, evenements.stream(ControleFichiers.FichierInfecte.class).count());
    }
}
