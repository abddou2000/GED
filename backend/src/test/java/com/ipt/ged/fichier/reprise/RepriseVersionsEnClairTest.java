package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.DepotClesFichierJdbc;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.fichier.integrite.SourceEmpreintesVersions;
import com.ipt.ged.fichier.integrite.VerificationIntegrite;
import com.ipt.ged.fichier.integrite.VerificationPeriodique;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import com.ipt.ged.ocr.file.EnfilageOcr;
import com.ipt.ged.ocr.file.OcrJobQueuePostgres;
import com.ipt.ged.ocr.file.PolitiqueReprise;
import com.ipt.ged.ocr.file.StatutOcr;
import com.ipt.ged.ocr.moteur.LanguesOcr;
import com.ipt.ged.support.BasePostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reprise des fichiers en clair de l'ancien stockage vers le stockage chiffré,
 * pilotée par {@code version_document} (PostgreSQL réel, changelog maître), puis
 * vérification d'intégrité mensuelle sur les empreintes reprises et application
 * du changeset « contract » de la version suivante.
 */
class RepriseVersionsEnClairTest {

    private static final String CONTRACT = "db/version-suivante-test/contract.xml";

    @TempDir Path dossier;

    private BasePostgres base;
    private JdbcTemplate jdbc;
    private Path ancien;
    private StockageChiffre stockage;
    private OcrJobQueuePostgres file;
    private RepriseVersionsEnClair reprise;

    @BeforeEach
    void preparer() throws Exception {
        base = BasePostgres.ouvrir();
        jdbc = base.jdbc();
        ancien = Files.createDirectories(dossier.resolve("storage/ged"));
        stockage = new StockageChiffre(new FileStoreDisque(dossier.resolve("coffre")),
                new KeystoreKeyProvider(dossier.resolve("cles/kek.p12"), "mdp".toCharArray(), true),
                new DepotClesFichierJdbc(jdbc));
        file = new OcrJobQueuePostgres(jdbc, PolitiqueReprise.PAR_DEFAUT);
        reprise = new RepriseVersionsEnClair(jdbc, new TransactionTemplate(new DataSourceTransactionManager(base.source())),
                stockage, new DetecteurTypeReel(), null, new EnfilageOcr(file, new LanguesOcr("fra+ara", Map.of()), true),
                200L * 1024 * 1024);
    }

    @AfterEach
    void fermer() throws Exception {
        base.close();
    }

    private UUID ancienneVersion(UUID doc, String chemin, byte[] contenu, boolean courante) throws Exception {
        UUID v = UUID.randomUUID();
        if (contenu != null) {
            Path f = ancien.resolve(chemin);
            Files.createDirectories(f.getParent());
            Files.write(f, contenu);
        }
        base.version(doc, v, chemin, courante);
        return v;
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private Map<String, Object> ligne(UUID version) {
        return jdbc.queryForMap("SELECT cle_fichier_id, empreinte, type_mime, taille_octets FROM version_document WHERE id = ?",
                version);
    }

    @Test
    @DisplayName("Chaque version en clair est chiffrée, renseignée (clé, empreinte, type, taille) ; la courante part à l'OCR")
    void reprise() throws Exception {
        UUID doc = UUID.randomUUID();
        byte[] v1 = Echantillons.pdf(), v2 = Echantillons.docx();
        UUID ancienneV = ancienneVersion(doc, "1/" + UUID.randomUUID() + ".pdf", v1, false);
        UUID courante = ancienneVersion(doc, "1/" + UUID.randomUUID() + ".docx", v2, true);
        Path rapport = dossier.resolve("reprise.csv");

        RepriseVersionsEnClair.Rapport r = reprise.reprendre(ancien, rapport);
        assertEquals(2, r.reprises());
        assertEquals(0, r.echecs());

        Map<String, Object> l1 = ligne(ancienneV), l2 = ligne(courante);
        assertEquals(sha256(v1), l1.get("empreinte"));
        assertEquals("application/pdf", l1.get("type_mime"));
        assertEquals((long) v1.length, ((Number) l1.get("taille_octets")).longValue());
        assertEquals(sha256(v2), l2.get("empreinte"));
        try (InputStream in = stockage.lire((UUID) l2.get("cle_fichier_id"))) {
            assertArrayEquals(v2, in.readAllBytes());
        }
        // Seule la version courante est indexée : l'index porte sur elle (§4.4).
        assertEquals(StatutOcr.EN_ATTENTE_OCR, file.statutsParVersion(List.of(courante)).get(courante));
        assertNull(file.statutsParVersion(List.of(ancienneV)).get(ancienneV));
        // Originaux intacts, rapport complet.
        assertArrayEquals(v1, Files.readAllBytes(ancien.resolve(jdbc.queryForObject(
                "SELECT file_path FROM version_document WHERE id = ?", String.class, ancienneV))));
        List<String> lignes = Files.readAllLines(rapport, StandardCharsets.UTF_8);
        assertEquals(RepriseVersionsEnClair.ENTETE, lignes.get(0));
        assertEquals(3, lignes.size());
        assertTrue(lignes.stream().skip(1).allMatch(x -> x.endsWith(";OK")));

        // Reprenable : rien n'est refait.
        assertEquals(0, reprise.reprendre(ancien, rapport).reprises());
    }

    @Test
    @DisplayName("Fichier absent ou chemin hors de l'ancienne racine : signalés, non repris, la reprise continue")
    void anomalies() throws Exception {
        UUID doc = UUID.randomUUID();
        UUID absent = ancienneVersion(doc, "1/absent.pdf", null, false);
        UUID evasion = ancienneVersion(doc, "../../hors-racine.pdf", null, false);
        UUID bonne = ancienneVersion(doc, "1/ok.pdf", Echantillons.pdf(), true);
        Files.write(dossier.resolve("hors-racine.pdf"), Echantillons.pdf());

        Path rapport = dossier.resolve("r.csv");
        RepriseVersionsEnClair.Rapport r = reprise.reprendre(ancien, rapport);
        assertEquals(1, r.reprises());
        assertEquals(2, r.echecs());
        String csv = Files.readString(rapport);
        assertTrue(csv.contains(absent + ";1/absent.pdf;;;;;FICHIER_ABSENT"), csv);
        assertTrue(csv.contains(evasion + ";../../hors-racine.pdf;;;;;CHEMIN_HORS_RACINE"), csv);
        assertNull(ligne(absent).get("cle_fichier_id"));
        assertNotNull(ligne(bonne).get("cle_fichier_id"));
    }

    @Test
    @DisplayName("Vérification mensuelle sur les versions reprises : toutes conformes, une altération détectée")
    void verificationMensuelle() throws Exception {
        UUID doc = UUID.randomUUID();
        UUID a = ancienneVersion(doc, "2/a.pdf", Echantillons.pdf(), false);
        UUID b = ancienneVersion(doc, "2/b.png", Echantillons.image("png"), true);
        reprise.reprendre(ancien, dossier.resolve("r.csv"));

        VerificationIntegrite verification = new VerificationIntegrite(stockage, e -> { });
        VerificationPeriodique mensuelle = new VerificationPeriodique(verification, new SourceEmpreintesVersions(jdbc));
        assertEquals(Map.of(VerificationIntegrite.Statut.CONFORME, 2), mensuelle.executer());

        // Empreinte enregistrée falsifiée : la divergence est détectée. L'empreinte
        // d'une version est immuable (lot modèle, déclencheur de lecture seule) :
        // la falsification simulée est celle d'un propriétaire du schéma qui
        // contourne le déclencheur, seul chemin qui reste.
        jdbc.execute("ALTER TABLE version_document DISABLE TRIGGER trg_version_document_lecture_seule");
        jdbc.update("UPDATE version_document SET empreinte = ? WHERE id = ?", "0".repeat(64), a);
        jdbc.execute("ALTER TABLE version_document ENABLE TRIGGER trg_version_document_lecture_seule");
        Map<VerificationIntegrite.Statut, Integer> bilan = mensuelle.executer();
        assertEquals(1, bilan.get(VerificationIntegrite.Statut.EMPREINTE_DIVERGENTE));
        assertEquals(1, bilan.get(VerificationIntegrite.Statut.CONFORME));
        assertNotNull(b);
    }

    @Test
    @DisplayName("Contract (version suivante) : reporté tant qu'une version n'est pas reprise, appliqué ensuite, réversible")
    void contract() throws Exception {
        UUID doc = UUID.randomUUID();
        UUID absent = ancienneVersion(doc, "3/absent.pdf", null, false);
        ancienneVersion(doc, "3/ok.pdf", Echantillons.pdf(), true);
        reprise.reprendre(ancien, dossier.resolve("r.csv"));

        // Une version reste sans clé : le changeset est sauté, les chemins restent.
        base.appliquer(CONTRACT);
        assertEquals(1, colonnesChemin());

        // Reprise terminée (la version orpheline est retirée) : le contract s'applique.
        jdbc.update("DELETE FROM version_document WHERE id = ?", absent);
        base.appliquer(CONTRACT);
        assertEquals(0, colonnesChemin());
        assertEquals("NO", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'version_document' AND column_name = 'cle_fichier_id'", String.class, base.schema()));
        // La reprise constate qu'elle est sans objet.
        assertEquals(0, reprise.reprendre(ancien, dossier.resolve("r2.csv")).reprises());

        base.annuler(CONTRACT, 1);
        assertEquals(1, colonnesChemin());
    }

    private int colonnesChemin() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'version_document' AND column_name = 'file_path'", Integer.class, base.schema());
    }
}
