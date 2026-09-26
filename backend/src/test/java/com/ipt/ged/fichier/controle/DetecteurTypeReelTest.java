package com.ipt.ged.fichier.controle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** §6.1.5 — type réel déterminé par le contenu (Apache Tika), jamais par l'extension. */
class DetecteurTypeReelTest {

    private final DetecteurTypeReel detecteur = new DetecteurTypeReel();

    private String type(byte[] contenu, String nom) {
        return detecteur.detecter(SourceFichier.de(contenu, nom));
    }

    @Test
    @DisplayName("Les douze formats de la liste par défaut sont reconnus par leur contenu")
    void formatsParDefaut() throws Exception {
        assertEquals("application/pdf", type(Echantillons.pdf(), "a"));
        assertEquals("image/tiff", type(Echantillons.image("tiff"), "a"));
        assertEquals("image/jpeg", type(Echantillons.image("jpeg"), "a"));
        assertEquals("image/png", type(Echantillons.image("png"), "a"));
        assertEquals("text/plain", type("Compte rendu de réunion\nPoint 1".getBytes(StandardCharsets.UTF_8), "a"));
        assertEquals("text/plain", type("code;libelle;montant\n1;Loyer;1200\n".getBytes(StandardCharsets.UTF_8), "a"));
        assertEquals(FormatsReconnus.DOCX, type(Echantillons.docx(), "a"));
        assertEquals(FormatsReconnus.XLSX, type(Echantillons.xlsx(), "a"));
        assertEquals(FormatsReconnus.PPTX, type(Echantillons.pptx(), "a"));
        assertEquals(FormatsReconnus.ODT, type(Echantillons.odf(FormatsReconnus.ODT), "a"));
        assertEquals(FormatsReconnus.ODS, type(Echantillons.odf(FormatsReconnus.ODS), "a"));
        assertEquals(FormatsReconnus.ODP, type(Echantillons.odf(FormatsReconnus.ODP), "a"));

        Set<String> admis = FormatsReconnus.typesAdmis(FormatsReconnus.PAR_DEFAUT);
        for (byte[] f : List.of(Echantillons.pdf(), Echantillons.image("png"), Echantillons.docx(),
                Echantillons.xlsx(), Echantillons.odf(FormatsReconnus.ODS))) {
            assertTrue(admis.contains(type(f, "x")));
        }
    }

    @Test
    @DisplayName("L'extension est ignorée : un exécutable nommé .pdf reste un exécutable")
    void extensionIgnoree() {
        String t = type(Echantillons.executable(), "facture.pdf");
        assertNotEquals("application/pdf", t);
        assertFalse(FormatsReconnus.typesAdmis(FormatsReconnus.PAR_DEFAUT).contains(t), t);
    }

    @Test
    @DisplayName("Un vrai PDF nommé .exe reste un PDF")
    void pdfMalNomme() throws Exception {
        assertEquals("application/pdf", type(Echantillons.pdf(), "setup.exe"));
    }

    @Test
    @DisplayName("Formats hors liste blanche détectés comme tels : HTML, archive, Word à macros")
    void horsListe() throws Exception {
        Set<String> admis = FormatsReconnus.typesAdmis(FormatsReconnus.PAR_DEFAUT);
        String html = type("<!DOCTYPE html><html><body><script>alert(1)</script></body></html>"
                .getBytes(StandardCharsets.UTF_8), "note.txt");
        assertEquals("text/html", html);
        assertFalse(admis.contains(html));
        String zip = type(Echantillons.zipQuelconque(), "rapport.docx");
        assertEquals("application/zip", zip);
        assertFalse(admis.contains(zip));
        String docm = type(Echantillons.docm(), "contrat.docx");
        assertEquals("application/vnd.ms-word.document.macroEnabled.12", docm);
        assertFalse(admis.contains(docm));
    }

    @Test
    @DisplayName("Liste blanche par type : extensions paramétrées traduites en types réels")
    void listeBlanche() {
        assertEquals(Set.of("application/pdf"), FormatsReconnus.typesAdmis(List.of("PDF")));
        assertTrue(FormatsReconnus.typesAdmis(List.of("csv")).contains("text/plain"));
        assertTrue(FormatsReconnus.typesAdmis(List.of("jpg")).contains("image/jpeg"));
        assertTrue(FormatsReconnus.typesAdmis(List.of("exe", "zip")).isEmpty(), "une extension inconnue n'ouvre rien");
    }
}
