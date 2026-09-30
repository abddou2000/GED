package com.ipt.ged.cycledevie;

import com.ipt.ged.cycledevie.conservation.ValidateurPdfA;
import com.ipt.ged.document.evenement.ApercuConsulte;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.previsualisation.ConvertisseurBureautique;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Conversion bureautique par le <b>vrai</b> LibreOffice du poste (T-060, T-064,
 * §6.1.4, §6.1.6) : aperçu d'un Word converti en PDF à la volée, et copie de
 * conservation PDF/A-2 d'un Word validée par veraPDF. Le profil de test simule
 * LibreOffice absent ; cette classe le rétablit ({@code soffice} du PATH, ou
 * {@code GED_LIBREOFFICE}). Sans LibreOffice sur le poste, elle est ignorée
 * (et non réussie) : le comportement « absent » est prouvé par
 * {@code ArchivageApiTest} et {@code ApercuTelechargementApiTest}.
 */
@RecordApplicationEvents
@TestPropertySource(properties = "ged.fichiers.previsualisation.libreoffice-commande=${GED_LIBREOFFICE:soffice}")
class LibreOfficeReelApiTest extends BaseCycleDeVieApiTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private ConvertisseurBureautique libreOffice;
    @Autowired private ValidateurPdfA validateur;
    @Autowired private ApplicationEvents evenements;

    private UUID type;

    @BeforeEach
    void preparer() {
        assumeTrue(libreOffice.disponible(), "LibreOffice absent du poste : conversion réelle non vérifiable ici");
        type = type(espace("LibreOffice " + suffixe, null), "pdf,docx");
    }

    private static String texte(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    @DisplayName("T-064 : aperçu d'un Word converti en PDF par LibreOffice, audité, sans téléchargement")
    void apercuWord() throws Exception {
        UUID doc = deposer(type, "contrat", "contrat.docx", DOCX, Echantillons.docx());
        UUID version = jdbc.queryForObject("SELECT id FROM version_document WHERE document_id = ?", UUID.class, doc);
        byte[] pdf = mvc.perform(get("/api/v1/versions/{id}/apercu", version))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
        assertTrue(texte(pdf).contains("Contrat de prestation"), "le PDF rend le contenu du Word");
        assertEquals(1, evenements.stream(ApercuConsulte.class).filter(e -> e.documentId().equals(doc)).count());
        // Second aperçu servi depuis le cache chiffré : même contenu.
        byte[] encore = mvc.perform(get("/api/v1/versions/{id}/apercu", version))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertTrue(texte(encore).contains("Contrat de prestation"));
    }

    @Test
    @DisplayName("T-060 : archivage d'un Word, copie PDF/A-2 par LibreOffice validée par veraPDF, original conservé")
    void archivageWord() throws Exception {
        byte[] docx = Echantillons.docx();
        UUID doc = deposer(type, "marche", "marche.docx", DOCX, docx);
        mvc.perform(post("/api/v1/documents/" + doc + "/archivage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue").value("ARCHIVE"))
                .andExpect(jsonPath("$.copieConservation").value("VALIDE"));
        mvc.perform(get("/api/v1/documents/" + doc + "/conservation"))
                .andExpect(jsonPath("$.copie.statut").value("VALIDE"))
                .andExpect(jsonPath("$.copie.format").value("PDF/A-2B"));

        byte[] servie = flux("/api/v1/documents/" + doc + "/download");
        Path pdf = Files.createTempFile("copie-lo-", ".pdf");
        try {
            Files.write(pdf, servie);
            ValidateurPdfA.Validation v = validateur.valider(pdf);
            assertTrue(v.conforme(), v.detail());
        } finally {
            Files.deleteIfExists(pdf);
        }
        assertTrue(texte(servie).contains("Contrat de prestation"), "la copie rend le contenu du Word");
        assertArrayEquals(docx, flux("/api/v1/documents/" + doc + "/download?original=true"));
    }
}
