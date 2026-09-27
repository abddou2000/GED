package com.ipt.ged.cycledevie;

import com.ipt.ged.cycledevie.conservation.ValidateurPdfA;
import com.ipt.ged.document.evenement.DocumentArchive;
import com.ipt.ged.document.evenement.DocumentDesarchive;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.integrite.VerificationIntegrite;
import com.ipt.ged.workspace.WorkSpace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Archivage d'un document (§12.6, revue client D10) : empreinte revérifiée,
 * copie PDF/A-2 validée par veraPDF et chiffrée, original conservé, lecture
 * seule totale (service et base), désarchivage.
 */
@RecordApplicationEvents
class ArchivageApiTest extends BaseCycleDeVieApiTest {

    @Autowired private ValidateurPdfA validateur;
    @Autowired private ApplicationEvents evenements;

    private UUID type;

    @BeforeEach
    void preparer() {
        WorkSpace w = espace("Archivage " + suffixe, null);
        type = type(w, "pdf,png,docx");
    }

    @Test
    @DisplayName("Archivage d'un PDF : copie PDF/A-2 conforme et chiffrée, servie par défaut ; original conservé")
    void archivagePdf() throws Exception {
        byte[] original = Echantillons.pdf();
        UUID doc = deposer(type, "rapport", "rapport.pdf", "application/pdf", original);

        mvc.perform(post("/api/v1/documents/" + doc + "/archivage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue").value("ARCHIVE"))
                .andExpect(jsonPath("$.copieConservation").value("VALIDE"));

        mvc.perform(get("/api/v1/documents/" + doc))
                .andExpect(jsonPath("$.statutConservation").value("ARCHIVE"))
                .andExpect(jsonPath("$.archiveLe").isNotEmpty());
        mvc.perform(get("/api/v1/documents/" + doc + "/conservation"))
                .andExpect(jsonPath("$.copie.statut").value("VALIDE"))
                .andExpect(jsonPath("$.copie.format").value("PDF/A-2B"))
                .andExpect(jsonPath("$.copie.empreinte").isNotEmpty());

        // Copie chiffrée comme tout fichier (clé de fichier propre).
        UUID copie = jdbc.queryForObject("SELECT c.cle_fichier_id FROM copie_conservation c "
                + "JOIN version_document v ON v.id = c.version_id WHERE v.document_id = ?", UUID.class, doc);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cle_fichier WHERE id = ?", Integer.class, copie));
        assertFalse(new String(Files.readAllBytes(fichierChiffre(copie)), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("%PDF"), "copie jamais en clair sur disque");

        // Copie servie par défaut, validée par veraPDF ; original sur demande, intact.
        byte[] servie = flux("/api/v1/documents/" + doc + "/download");
        Path pdf = Files.createTempFile("copie-", ".pdf");
        try {
            Files.write(pdf, servie);
            ValidateurPdfA.Validation v = validateur.valider(pdf);
            assertTrue(v.conforme(), v.detail());
        } finally {
            Files.deleteIfExists(pdf);
        }
        assertArrayEquals(original, flux("/api/v1/documents/" + doc + "/download?original=true"));

        DocumentArchive e = evenements.stream(DocumentArchive.class).filter(x -> x.documentId().equals(doc))
                .findFirst().orElseThrow();
        assertEquals("DOCUMENT_ARCHIVE", e.action());
        assertEquals("VALIDE", e.copieConservation());
        assertNotNull(e.acteur().employeId());
    }

    @Test
    @DisplayName("Document archivé : toute écriture refusée en 409 (fiche, index, version, verrou, corbeille), doublé en base")
    void lectureSeule() throws Exception {
        UUID doc = deposer(type, "gel", "gel.png", "image/png", Echantillons.image("png"));
        mvc.perform(post("/api/v1/documents/" + doc + "/archivage")).andExpect(status().isOk());

        mvc.perform(put("/api/v1/documents/" + doc).contentType(APPLICATION_JSON).content("{\"name\":\"autre\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ErreurCycleDeVie.DOCUMENT_ARCHIVE));
        mvc.perform(patch("/api/v1/documents/" + doc + "/verrou").param("verrouille", "true"))
                .andExpect(status().isConflict());
        mvc.perform(multipart("/api/v1/documents/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "v2.png", "image/png", Echantillons.image("png"))))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/documents/" + doc)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/indexation/documents/" + doc).contentType(APPLICATION_JSON).content("{\"valeurs\":[]}"))
                .andExpect(status().isConflict());
        UUID version = jdbc.queryForObject("SELECT id FROM version_document WHERE document_id = ?", UUID.class, doc);
        mvc.perform(patch("/api/v1/documents/" + doc + "/versions/" + version + "/default"))
                .andExpect(status().isConflict());
        // Déjà archivé : 409.
        mvc.perform(post("/api/v1/documents/" + doc + "/archivage")).andExpect(status().isConflict());

        // Contrainte en base : les versions d'un document archivé sont gelées.
        Exception ex = assertThrows(Exception.class,
                () -> jdbc.update("UPDATE version_document SET observation = 'x' WHERE document_id = ?", doc));
        assertTrue(ex.getMessage().contains("archivé"), ex.getMessage());
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM version_document WHERE document_id = ?", doc));

        // Désarchivage : réversible, audité, l'écriture redevient possible.
        mvc.perform(delete("/api/v1/documents/" + doc + "/archivage")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.statutConservation").value("ACTIF"));
        assertEquals(1, evenements.stream(DocumentDesarchive.class).filter(x -> x.documentId().equals(doc)).count());
        mvc.perform(put("/api/v1/documents/" + doc).contentType(APPLICATION_JSON).content("{\"name\":\"autre\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/" + doc + "/archivage")).andExpect(status().isConflict());

        // Réarchivage : la copie de la même version est réutilisée, pas reproduite.
        int copies = jdbc.queryForObject("SELECT count(*) FROM cle_fichier", Integer.class);
        mvc.perform(post("/api/v1/documents/" + doc + "/archivage")).andExpect(status().isOk());
        assertEquals(copies, jdbc.queryForObject("SELECT count(*) FROM cle_fichier", Integer.class));
    }

    @Test
    @DisplayName("Word sans LibreOffice : archivé avec son original, copie ECHEC signalée (anomalie), original servi")
    void conversionEnEchec() throws Exception {
        byte[] docx = Echantillons.docx();
        UUID doc = deposer(type, "contrat", "contrat.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        mvc.perform(post("/api/v1/documents/" + doc + "/archivage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue").value("ANOMALIE"))
                .andExpect(jsonPath("$.copieConservation").value("ECHEC"));
        mvc.perform(get("/api/v1/documents/" + doc)).andExpect(jsonPath("$.statutConservation").value("ARCHIVE"));
        mvc.perform(get("/api/v1/documents/" + doc + "/conservation"))
                .andExpect(jsonPath("$.copie.statut").value("ECHEC"))
                .andExpect(jsonPath("$.copie.motif").value(org.hamcrest.Matchers.containsString("LibreOffice")));
        assertArrayEquals(docx, flux("/api/v1/documents/" + doc + "/download"));
        DocumentArchive e = evenements.stream(DocumentArchive.class).filter(x -> x.documentId().equals(doc))
                .findFirst().orElseThrow();
        assertEquals("ECHEC", e.copieConservation());
        assertNotNull(e.motif());
    }

    @Test
    @DisplayName("Refus : verrouillé, en corbeille, inconnu ; empreinte divergente = 500 INTEGRITE_COMPROMISE et anomalie")
    void refus() throws Exception {
        UUID verrouille = deposer(type, "v", "v.pdf", "application/pdf", Echantillons.pdf());
        mvc.perform(patch("/api/v1/documents/" + verrouille + "/verrou").param("verrouille", "true"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/documents/" + verrouille + "/archivage"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ErreurCycleDeVie.DOCUMENT_VERROUILLE));

        UUID corbeille = deposer(type, "c", "c.pdf", "application/pdf", Echantillons.pdf());
        mvc.perform(delete("/api/v1/documents/" + corbeille)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/documents/" + corbeille + "/archivage"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ErreurCycleDeVie.DOCUMENT_EN_CORBEILLE));

        mvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/archivage")).andExpect(status().isNotFound());

        // Fichier chiffré altéré : l'archivage refuse et signale l'anomalie d'intégrité.
        UUID altere = deposer(type, "a", "a.pdf", "application/pdf", Echantillons.pdf());
        Path chiffre = fichierChiffre(fichierCourant(altere));
        byte[] octets = Files.readAllBytes(chiffre);
        octets[octets.length - 20] ^= 0x5A;
        Files.write(chiffre, octets);
        mvc.perform(post("/api/v1/documents/" + altere + "/archivage"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTEGRITE_COMPROMISE"));
        mvc.perform(get("/api/v1/documents/" + altere)).andExpect(jsonPath("$.statutConservation").value("ACTIF"));
        assertTrue(evenements.stream(VerificationIntegrite.AnomalieIntegrite.class).findAny().isPresent());
    }

    @Test
    @DisplayName("Recherche multicritère : archivés inclus par défaut, filtre pour les exclure ou ne garder qu'eux")
    void recherche() throws Exception {
        UUID actif = deposer(type, "actif", "actif.pdf", "application/pdf", Echantillons.pdf());
        UUID archive = deposer(type, "archive", "archive.pdf", "application/pdf", Echantillons.pdf());
        mvc.perform(post("/api/v1/documents/" + archive + "/archivage")).andExpect(status().isOk());
        String tous = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\"}")).andReturn().getResponse().getContentAsString();
        assertTrue(tous.contains(actif.toString()) && tous.contains(archive.toString()));
        assertTrue(tous.contains("\"statutConservation\":\"ARCHIVE\""));
        String sans = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"archives\":\"EXCLURE\"}")).andReturn().getResponse().getContentAsString();
        assertTrue(sans.contains(actif.toString()) && !sans.contains(archive.toString()));
        String seuls = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"archives\":\"SEULEMENT\"}")).andReturn().getResponse().getContentAsString();
        assertTrue(!seuls.contains(actif.toString()) && seuls.contains(archive.toString()));
    }
}
