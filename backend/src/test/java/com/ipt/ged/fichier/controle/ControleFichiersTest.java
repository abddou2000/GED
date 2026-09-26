package com.ipt.ged.fichier.controle;

import com.ipt.ged.fichier.DepotClesFichierMemoire;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.5 — chaîne de contrôle au dépôt : taille → type réel → antivirus →
 * écriture chiffrée. Un refus ne laisse aucune trace dans le référentiel.
 */
class ControleFichiersTest {

    private static final long MO = 1024L * 1024L;

    @TempDir Path dossier;

    private FauxClamd clamd;
    private FileStoreDisque store;
    private DepotClesFichierMemoire cles;
    private List<Object> evenements;
    private ControleFichiers controle;

    @BeforeEach
    void preparer() throws Exception {
        clamd = new FauxClamd();
        store = new FileStoreDisque(dossier.resolve("coffre"));
        cles = new DepotClesFichierMemoire();
        evenements = new ArrayList<>();
        StockageChiffre stockage = new StockageChiffre(store,
                new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mdp".toCharArray(), true), cles);
        controle = new ControleFichiers(new DetecteurTypeReel(),
                new ClientClamd("127.0.0.1", clamd.port(), 1000, 5000, 64 * 1024),
                stockage, evenements::add, 100, 200, FormatsReconnus.PAR_DEFAUT);
    }

    @AfterEach
    void arreter() throws Exception {
        clamd.close();
    }

    private ReglesDepot pdfSeulement() {
        return controle.regles(10, List.of("pdf"));
    }

    private void assertRienEcrit() throws Exception {
        List<UUID> publies = new ArrayList<>();
        store.parcourir(publies::add);
        assertTrue(publies.isEmpty(), "aucun fichier ne doit être écrit");
        assertEquals(0, cles.taille(), "aucune clé ne doit être enregistrée");
    }

    /** Source dont la taille annoncée est donnée sans allouer le contenu. */
    private static SourceFichier annoncee(long taille, byte[] contenu) {
        return new SourceFichier() {
            @Override public InputStream ouvrir() { return new ByteArrayInputStream(contenu); }
            @Override public long taille() { return taille; }
            @Override public String nomOrigine() { return "gros.pdf"; }
        };
    }

    @Test
    @DisplayName("Fichier conforme : contrôlé, chiffré, empreinte du clair rendue")
    void depotConforme() throws Exception {
        byte[] pdf = Echantillons.pdf();
        ControleFichiers.Depot d = controle.deposer(SourceFichier.de(pdf, "facture.pdf"), pdfSeulement());
        assertEquals("application/pdf", d.typeMime());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)), d.stockage().empreinte());
        assertEquals(pdf.length, d.stockage().tailleOctets());
        assertEquals(1, clamd.connexions.get());
        assertTrue(store.existe(d.stockage().id()));
    }

    @Test
    @DisplayName("Taille dépassée → 413, avant toute lecture du contenu ni analyse antivirus")
    void tailleDAbord() throws Exception {
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(annoncee(10 * MO + 1, new byte[0]), pdfSeulement()));
        assertEquals(413, e.statut().value());
        assertEquals("FICHIER_TROP_VOLUMINEUX", e.code());
        assertEquals(0, clamd.connexions.get());
        assertRienEcrit();
    }

    @Test
    @DisplayName("Type réel hors liste → 415, sans analyse antivirus")
    void typeAvantAntivirus() throws Exception {
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(SourceFichier.de(Echantillons.executable(), "facture.pdf"), pdfSeulement()));
        assertEquals(415, e.statut().value());
        assertEquals("FORMAT_NON_AUTORISE", e.code());
        assertEquals(0, clamd.connexions.get());
        assertRienEcrit();
    }

    @Test
    @DisplayName("Un docx déposé sur un type « pdf » est refusé (415), même nommé .pdf")
    void mauvaisFormatAutorise() throws Exception {
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(SourceFichier.de(Echantillons.docx(), "contrat.pdf"), pdfSeulement()));
        assertEquals(415, e.statut().value());
        assertRienEcrit();
    }

    @Test
    @DisplayName("Fichier infecté (EICAR) → 422 FICHIER_INFECTE, événement d'audit, rien d'écrit")
    void infecte() throws Exception {
        byte[] eicar = FauxClamd.eicar().getBytes(StandardCharsets.US_ASCII);
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(SourceFichier.de(eicar, "note.txt"), controle.regles(null, List.of())));
        assertEquals(422, e.statut().value());
        assertEquals("FICHIER_INFECTE", e.code());
        assertRienEcrit();
        assertEquals(1, evenements.size());
        ControleFichiers.FichierInfecte ev = (ControleFichiers.FichierInfecte) evenements.get(0);
        assertEquals("note.txt", ev.nomOrigine());
    }

    @Test
    @DisplayName("ClamAV indisponible → dépôt refusé (échec fermé), rien d'écrit")
    void echecFerme() throws Exception {
        int portLibre;
        try (ServerSocket s = new ServerSocket(0)) {
            portLibre = s.getLocalPort();
        }
        StockageChiffre stockage = new StockageChiffre(store,
                new KeystoreKeyProvider(dossier.resolve("kek.p12"), "mdp".toCharArray(), false), cles);
        ControleFichiers sansClamav = new ControleFichiers(new DetecteurTypeReel(),
                new ClientClamd("127.0.0.1", portLibre, 300, 1000, 8192), stockage, evenements::add,
                100, 200, FormatsReconnus.PAR_DEFAUT);
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> sansClamav.deposer(SourceFichier.de(Echantillons.pdf(), "a.pdf"), pdfSeulement()));
        assertEquals(503, e.statut().value());
        assertEquals("ANTIVIRUS_INDISPONIBLE", e.code());
        assertRienEcrit();
    }

    @Test
    @DisplayName("Taille annoncée mensongère : arrêtée pendant l'écriture (413), rien de publié")
    void tailleMensongere() throws Exception {
        byte[] pdf = Echantillons.pdf();
        ReglesDepot regles = new ReglesDepot(pdf.length - 1, FormatsReconnus.typesAdmis(List.of("pdf")), List.of("pdf"));
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(annoncee(10, pdf), regles));
        assertEquals(413, e.statut().value());
        assertRienEcrit();
    }

    @Test
    @DisplayName("Tailles : 100 Mo par défaut, paramétrable par type, jamais au-delà du plafond de 200 Mo")
    void reglesDeTaille() {
        assertEquals(100 * MO, controle.regles(null, List.of()).tailleMaxOctets());
        assertEquals(100 * MO, controle.regles(0, List.of()).tailleMaxOctets());
        assertEquals(10 * MO, controle.regles(10, List.of()).tailleMaxOctets());
        assertEquals(200 * MO, controle.regles(200, List.of()).tailleMaxOctets());
        assertEquals(200 * MO, controle.regles(500, List.of()).tailleMaxOctets(), "plafond de plateforme");
        assertThrows(IllegalStateException.class, () -> new ControleFichiers(null, null, null, e -> { }, 300, 200, List.of()));
    }

    @Test
    @DisplayName("Au-delà du plafond de plateforme même si le type autorise plus → 413")
    void plafondPlateforme() {
        ErreurFichierException e = assertThrows(ErreurFichierException.class,
                () -> controle.deposer(annoncee(200 * MO + 1, new byte[0]), controle.regles(500, List.of("pdf"))));
        assertEquals(413, e.statut().value());
    }

    @Test
    @DisplayName("Liste blanche : vide = liste par défaut du dossier ; sinon celle du type")
    void listes() {
        ReglesDepot defaut = controle.regles(null, null);
        assertTrue(defaut.admet(FormatsReconnus.ODS));
        assertTrue(defaut.admet("image/tiff"));
        assertFalse(defaut.admet("application/zip"));
        ReglesDepot type = controle.regles(null, List.of("pdf", "docx"));
        assertTrue(type.admet(FormatsReconnus.DOCX));
        assertFalse(type.admet("image/png"));
    }

    @Test
    @DisplayName("Contrôle seul (aperçu d'indexation) : rend le type réel sans rien écrire")
    void controleSeul() throws Exception {
        assertEquals(FormatsReconnus.DOCX,
                controle.controler(SourceFichier.de(Echantillons.docx(), "c.docx"), controle.regles(null, null)));
        assertRienEcrit();
    }
}
