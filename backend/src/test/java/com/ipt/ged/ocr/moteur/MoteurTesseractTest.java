package com.ipt.ged.ocr.moteur;

import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.ocr.ExtracteurBureautique;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tesseract 5 réel, appelé depuis Java par entrée et sortie standard (D5) :
 * reconnaissance d'un scan arabe et d'un scan bilingue générés, avec les
 * modèles officiels {@code fra} et {@code ara} (tessdata_best) livrés dans
 * {@code backend/tessdata}.
 *
 * <p>Requiert le binaire Tesseract (installé sur les postes et serveurs de la
 * GED ; chemin surchargeable par {@code GED_TESSERACT}). Sans lui, ces tests
 * sont ignorés — le reste de la chaîne est couvert par
 * {@link ExtracteurDocumentOcrTest} avec un moteur simulé.
 */
class MoteurTesseractTest {

    private static final String COMMANDE = System.getenv().getOrDefault("GED_TESSERACT",
            "C:/Program Files/Tesseract-OCR/tesseract.exe");
    private static final String TESSDATA = Path.of("tessdata").toAbsolutePath().toString();
    private static final Duration DELAI = Duration.ofSeconds(60);

    private static MoteurTesseract moteur;
    private static String police;

    @BeforeAll
    static void preparer() {
        moteur = new MoteurTesseract(COMMANDE, TESSDATA, "1", "3");
        assumeTrue(moteur.disponible(), "Tesseract absent de ce poste : " + COMMANDE);
        police = Scans.policeArabe().orElse(null);
        assumeTrue(police != null, "aucune police arabe installée pour générer le scan");
    }

    private static String sansEspaces(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("Modèles fra et ara installés dans backend/tessdata")
    void modeles() {
        assertTrue(moteur.languesInstallees().containsAll(List.of("fra", "ara")), moteur.languesInstallees().toString());
    }

    @Test
    @DisplayName("Scan arabe reconnu avec ara, dans l'ordre logique des caractères")
    void arabe() throws Exception {
        String ligne = "عقد الإيجار السنوي للشركة";
        String texte = moteur.reconnaitre(Scans.png(Scans.page(police, List.of(ligne))), "ara", DELAI);
        assertEquals(sansEspaces(ligne), sansEspaces(texte), texte);
    }

    @Test
    @DisplayName("Scan bilingue reconnu avec fra+ara")
    void bilingue() throws Exception {
        String texte = moteur.reconnaitre(Scans.png(Scans.page(police, List.of(
                "محضر استلام الأشغال", "Procès-verbal de réception des travaux"))), "fra+ara", DELAI);
        assertTrue(sansEspaces(texte).contains(sansEspaces("محضر استلام الأشغال")), texte);
        assertTrue(texte.contains("Procès-verbal de réception des travaux"), texte);
    }

    @Test
    @DisplayName("Langue par défaut ara+fra : arabe et français (accents compris) reconnus sur un même scan")
    void bilingueArabePrincipal() throws Exception {
        String texte = moteur.reconnaitre(Scans.png(Scans.page(police, List.of(
                "عقد الإيجار السنوي للشركة", "Procès-verbal de réception définitive des travaux"))), "ara+fra", DELAI);
        assertTrue(sansEspaces(texte).contains(sansEspaces("عقد الإيجار السنوي للشركة")), texte);
        assertTrue(texte.contains("Procès-verbal de réception définitive des travaux"), texte);
    }

    @Test
    @DisplayName("PDF scanné de 3 pages : rendu 300 dpi en mémoire, OCR page par page, texte agrégé")
    void pdfScanne() throws Exception {
        byte[] pdf = Scans.pdf(List.of(
                Scans.page(police, List.of("Bordereau d'envoi numéro 117")),
                Scans.page(police, List.of("اتفاقية الشراكة")),
                "Page native : accuse de reception du bureau d'ordre digital."));
        ExtracteurDocumentOcr x = new ExtracteurDocumentOcr(moteur, new ExtracteurBureautique(), 300, 25, DELAI);
        long temporairesAvant = temporairesTesseract();
        TexteDocument t = x.extraire(new ByteArrayInputStream(pdf), FormatsReconnus.PDF, "fra+ara",
                ExtracteurDocumentOcr.SuiviPages.AUCUN);
        assertEquals(3, t.nbPages());
        assertEquals(2, t.pagesOcr());
        assertEquals(TexteDocument.Provenance.MIXTE, t.provenance());
        assertTrue(t.texte().contains("Bordereau d'envoi numéro 117"), t.texte());
        assertTrue(sansEspaces(t.texte()).contains(sansEspaces("اتفاقية الشراكة")), t.texte());
        assertTrue(t.texte().contains("accuse de reception"), t.texte());
        assertEquals(temporairesAvant, temporairesTesseract(), "aucune image écrite dans le répertoire temporaire");
    }

    @Test
    @DisplayName("Délai dépassé : processus tué, échec transitoire DELAI_DEPASSE")
    void delai() throws Exception {
        byte[] page = Scans.png(Scans.page(police, List.of("texte", "texte", "texte")));
        EchecOcrException e = assertThrows(EchecOcrException.class,
                () -> moteur.reconnaitre(page, "fra+ara", Duration.ofMillis(1)));
        assertEquals(EchecOcrException.Motif.DELAI_DEPASSE, e.motif());
        assertFalse(e.definitif());
    }

    @Test
    @DisplayName("Langue non installée ou mal formée : échec définitif, Tesseract non lancé")
    void langue() {
        assertEquals(EchecOcrException.Motif.LANGUE_NON_INSTALLEE,
                assertThrows(EchecOcrException.class, () -> moteur.reconnaitre(new byte[1], "deu", DELAI)).motif());
        assertEquals(EchecOcrException.Motif.LANGUE_NON_INSTALLEE,
                assertThrows(EchecOcrException.class, () -> moteur.reconnaitre(new byte[1], "fra --psm 0", DELAI)).motif());
    }

    @Test
    @DisplayName("Binaire absent : MOTEUR_INDISPONIBLE (transitoire), sans exception non typée")
    void absent() {
        MoteurTesseract m = new MoteurTesseract("tesseract-inexistant-e6", TESSDATA, "1", "3");
        assertFalse(m.disponible());
        EchecOcrException e = assertThrows(EchecOcrException.class, () -> m.reconnaitre(new byte[1], "fra", DELAI));
        assertEquals(EchecOcrException.Motif.MOTEUR_INDISPONIBLE, e.motif());
    }

    private static long temporairesTesseract() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().matches("(?i).*(\\.png|tess.*)$")).count();
        }
    }
}
