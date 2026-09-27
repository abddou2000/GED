package com.ipt.ged.ocr.file;

import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.ocr.ExtracteurBureautique;
import com.ipt.ged.ocr.moteur.EchecOcrException;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.OcrEngine;
import com.ipt.ged.recherche.PageResultats;
import com.ipt.ged.recherche.RequeteRecherche;
import com.ipt.ged.recherche.SearchIndexerPostgres;
import com.ipt.ged.support.BasePostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Worker OCR de bout en bout sur PostgreSQL réel : job réservé, texte extrait,
 * {@code document_texte} et clôture du job dans une même transaction,
 * document interrogeable, métrique {@code ocr_delai_disponibilite} ; échecs,
 * reprises et bail perdu.
 */
class TravailleurOcrPostgresTest {

    private static BasePostgres base;
    private static JdbcTemplate jdbc;

    private final Map<UUID, byte[]> fichiers = new ConcurrentHashMap<>();
    private OcrJobQueuePostgres file;
    private SearchIndexerPostgres indexer;
    private SimpleMeterRegistry registre;
    private MetriquesOcr metriques;
    private TransactionTemplate tx;

    /** Moteur factice : « reconnaît » le texte que le test a caché dans le nom de page. */
    private final OcrEngine moteur = new OcrEngine() {
        @Override public String nom() { return "factice"; }
        @Override public boolean disponible() { return true; }
        @Override public Set<String> languesInstallees() { return Set.of("fra", "ara"); }
        @Override public String reconnaitre(byte[] image, String langue, Duration delai) {
            return "scan reconnu en " + langue;
        }
    };

    @BeforeAll
    static void ouvrir() throws Exception {
        base = BasePostgres.ouvrir();
        jdbc = base.jdbc();
    }

    @AfterAll
    static void fermer() throws Exception {
        base.close();
    }

    @BeforeEach
    void preparer() {
        jdbc.update("DELETE FROM ocr_job");
        jdbc.update("DELETE FROM document_texte");
        file = new OcrJobQueuePostgres(jdbc, PolitiqueReprise.PAR_DEFAUT);
        indexer = new SearchIndexerPostgres(jdbc, (colonne, utilisateur) -> utilisateur != null && utilisateur.isAuthenticated()
                ? com.ipt.ged.recherche.FragmentSql.VRAI : com.ipt.ged.recherche.FragmentSql.FAUX);
        registre = new SimpleMeterRegistry();
        metriques = new MetriquesOcr(registre, Duration.ofHours(24), Clock.systemUTC());
        tx = new TransactionTemplate(new DataSourceTransactionManager(base.source()));
    }

    private TravailleurOcr travailleur(String nom, OcrEngine m) {
        ExtracteurDocumentOcr x = new ExtracteurDocumentOcr(m, new ExtracteurBureautique(), 72, 25, Duration.ofSeconds(60));
        SourceFichierOcr source = id -> {
            byte[] b = fichiers.get(id);
            if (b == null) throw new EchecOcrException(EchecOcrException.Motif.FICHIER_INTROUVABLE, id.toString());
            return new ByteArrayInputStream(b);
        };
        return new TravailleurOcr(nom, file, source, x, indexer, tx, metriques, Duration.ofMinutes(10));
    }

    private record Depot(UUID job, UUID document, UUID version) {}

    private Depot deposer(byte[] contenu, String type, Instant depose) {
        UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), fichier = UUID.randomUUID();
        base.document(doc, version);
        base.cleFichier(fichier);
        fichiers.put(fichier, contenu);
        UUID job = file.enfiler(new OcrJobQueue.NouveauJob(doc, version, fichier, type, "fra+ara", depose));
        return new Depot(job, doc, version);
    }

    private PageResultats chercher(String q) {
        return indexer.rechercher(RequeteRecherche.simple(q, 0, 20),
                new UsernamePasswordAuthenticationToken("u", null, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    @DisplayName("Dépôt → worker → document interrogeable ; délai mesuré ; job clos avec son nombre de pages")
    void boutEnBout() {
        Depot d = deposer("Convention d'occupation temporaire du domaine lagunaire".getBytes(StandardCharsets.UTF_8),
                "text/plain", Instant.now().minusSeconds(90));
        assertTrue(chercher("lagunaire").resultats().isEmpty(), "non interrogeable avant l'OCR");
        assertEquals(Map.of(d.version(), StatutOcr.EN_ATTENTE_OCR), file.statutsParVersion(java.util.List.of(d.version())));

        assertTrue(travailleur("w1", moteur).traiterUn());
        assertFalse(travailleur("w1", moteur).traiterUn(), "file vide");

        assertEquals(d.document(), chercher("lagunaire").resultats().get(0).documentId());
        OcrJob j = file.trouver(d.job()).orElseThrow();
        assertEquals(StatutOcr.OCR_TERMINE, j.statut());
        assertEquals(1, j.nbPages());
        assertEquals(1, registre.get("ged.ocr.delai.disponibilite").timer().count());
        double secondes = registre.get("ged.ocr.delai.disponibilite").timer().totalTime(TimeUnit.SECONDS);
        assertTrue(secondes >= 89 && secondes < 200, "délai mesuré depuis le dépôt : " + secondes);
        assertEquals(0.0, registre.get("ged.ocr.delai.objectif.depasse").counter().count());
    }

    @Test
    @DisplayName("PDF scanné traité par le moteur avec la langue du job ; provenance OCR enregistrée")
    void scan() throws Exception {
        Depot d = deposer(com.ipt.ged.ocr.moteur.Scans.pdf(java.util.List.of(
                new java.awt.image.BufferedImage(100, 140, java.awt.image.BufferedImage.TYPE_BYTE_GRAY))),
                FormatsReconnus.PDF, Instant.now());
        travailleur("w1", moteur).traiterUn();
        assertEquals("OCR", jdbc.queryForObject("SELECT provenance FROM document_texte WHERE document_id = ?",
                String.class, d.document()));
        assertEquals(d.document(), chercher("reconnu").resultats().get(0).documentId());
    }

    @Test
    @DisplayName("Objectif de 24 h dépassé : compteur incrémenté ; file supervisée (profondeur, âge)")
    void objectifDepasse() {
        deposer("vieux courrier".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now().minus(Duration.ofHours(30)));
        deposer("courrier récent".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now());
        FileOcrSupervisee supervisee = new FileOcrSupervisee(file, Clock.systemUTC());
        assertEquals("ocr", supervisee.nom());
        assertEquals(2, supervisee.profondeur());
        assertTrue(supervisee.ageDuPlusAncien().orElseThrow().toHours() >= 29);
        travailleur("w1", moteur).traiterUn(); // le plus ancien d'abord
        assertEquals(1.0, registre.get("ged.ocr.delai.objectif.depasse").counter().count());
        assertEquals(1, supervisee.profondeur());
    }

    @Test
    @DisplayName("Échec transitoire : reprise programmée ; échec définitif : OCR_ECHEC, document non interrogeable")
    void echecs() {
        OcrEngine enPanne = new OcrEngine() {
            @Override public String nom() { return "panne"; }
            @Override public boolean disponible() { return false; }
            @Override public Set<String> languesInstallees() { return Set.of(); }
            @Override public String reconnaitre(byte[] i, String l, Duration d) throws EchecOcrException {
                throw new EchecOcrException(EchecOcrException.Motif.MOTEUR_INDISPONIBLE, "Tesseract absent");
            }
        };
        Depot image = deposer(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png", Instant.now());
        travailleur("w1", enPanne).traiterUn();
        OcrJob j = file.trouver(image.job()).orElseThrow();
        assertEquals(StatutOcr.EN_ATTENTE_OCR, j.statut());
        assertTrue(j.motifEchec().startsWith("MOTEUR_INDISPONIBLE"));
        assertEquals(1.0, registre.get("ged.ocr.jobs").tag("issue", "reprise").counter().count());

        Depot zip = deposer(new byte[]{1}, "application/zip", Instant.now());
        travailleur("w1", moteur).traiterUn();
        OcrJob z = file.trouver(zip.job()).orElseThrow();
        assertEquals(StatutOcr.OCR_ECHEC, z.statut());
        assertTrue(z.motifEchec().startsWith("FORMAT_NON_SUPPORTE"), z.motifEchec());
        assertTrue(indexer.versionsIndexees(java.util.List.of(zip.version())).isEmpty());
        assertEquals(1.0, registre.get("ged.ocr.jobs").tag("issue", "echec").counter().count());
    }

    @Test
    @DisplayName("Fichier illisible (purgé) : OCR_ECHEC définitif")
    void fichierAbsent() {
        Depot d = deposer("x".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now());
        fichiers.clear();
        travailleur("w1", moteur).traiterUn();
        assertEquals(StatutOcr.OCR_ECHEC, file.trouver(d.job()).orElseThrow().statut());
    }

    @Test
    @DisplayName("Bail perdu pendant le traitement : aucun texte enregistré, le job reste à l'autre worker")
    void bailPerdu() {
        Depot d = deposer("texte du document au bail perdu".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now());
        OcrEngine voleur = moteur;
        // Le job est « volé » juste après la réservation : l'extraction réussit,
        // mais la clôture ne trouve plus le bail et annule la transaction.
        TravailleurOcr t = new TravailleurOcr("w1", new OcrJobQueuePostgres(jdbc, PolitiqueReprise.PAR_DEFAUT) {
            @Override public java.util.List<OcrJob> reserver(String w, int n, Duration b) {
                java.util.List<OcrJob> r = super.reserver(w, n, b);
                jdbc.update("UPDATE ocr_job SET verrouille_par = 'w2' WHERE id = ?", r.get(0).id());
                return r;
            }
        }, id -> new ByteArrayInputStream(fichiers.values().iterator().next()),
                new ExtracteurDocumentOcr(voleur, new ExtracteurBureautique(), 72, 25, Duration.ofSeconds(60)),
                indexer, tx, metriques, Duration.ofMinutes(10));
        t.traiterUn();
        assertTrue(chercher("perdu").resultats().isEmpty(), "transaction annulée : rien d'indexé");
        OcrJob j = file.trouver(d.job()).orElseThrow();
        assertEquals(StatutOcr.EN_COURS_OCR, j.statut());
        assertEquals("w2", j.verrouillePar());
    }

    @Test
    @DisplayName("Pool de 3 workers sur 30 dépôts : tout est indexé une fois, file vide")
    void pool() throws Exception {
        for (int i = 0; i < 30; i++) {
            deposer(("dossier numéro " + i + " de la marina").getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now());
        }
        PoolTravailleursOcr pool = new PoolTravailleursOcr(3, Duration.ofMillis(50), i -> travailleur("w" + i, moteur));
        pool.start();
        try {
            long limite = System.currentTimeMillis() + 30_000;
            while (file.profondeur() > 0 && System.currentTimeMillis() < limite) Thread.sleep(50);
        } finally {
            pool.stop();
        }
        assertEquals(0, file.profondeur());
        assertEquals(30L, file.compterParStatut().get(StatutOcr.OCR_TERMINE));
        assertEquals(30, indexer.compter());
        assertEquals(30, chercher("marina").total());
    }
}
