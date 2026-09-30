package com.ipt.ged.ocr.file;

import com.ipt.ged.support.BasePostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.3.4 — file {@code ocr_job} sur PostgreSQL 16 réel (base ged_dev3_test) :
 * réservation SKIP LOCKED sans doublon entre workers concurrents, bail,
 * reprises à 1, 5 puis 30 minutes, OCR_ECHEC avec motif, relance manuelle.
 */
class OcrJobQueuePostgresTest {

    private static final Duration BAIL = Duration.ofMinutes(10);
    private static BasePostgres base;
    private static JdbcTemplate jdbc;
    private OcrJobQueuePostgres file;

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
    void vider() {
        jdbc.update("DELETE FROM ocr_job");
        file = new OcrJobQueuePostgres(jdbc, PolitiqueReprise.PAR_DEFAUT);
    }

    private UUID enfiler() {
        return enfiler(Instant.now());
    }

    private UUID enfiler(Instant depose) {
        UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), fichier = UUID.randomUUID();
        base.document(doc, version);
        base.cleFichier(fichier);
        return file.enfiler(new OcrJobQueue.NouveauJob(doc, version, fichier, "application/pdf", "fra+ara", depose));
    }

    private OcrJob job(UUID id) {
        return file.trouver(id).orElseThrow();
    }

    /** Rend éligible une tentative programmée, sans attendre réellement. */
    private void avancerHorloge(UUID id) {
        jdbc.update("UPDATE ocr_job SET prochaine_tentative_le = now() - interval '1 second' WHERE id = ?", id);
    }

    private long secondesAvantProchaineTentative(UUID id) {
        return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM prochaine_tentative_le - now())::bigint FROM ocr_job WHERE id = ?",
                Long.class, id);
    }

    @Test
    @DisplayName("Enfilage : EN_ATTENTE_OCR, langue fra+ara, idempotent pour une même version")
    void enfilage() {
        UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), fichier = UUID.randomUUID();
        base.document(doc, version);
        base.cleFichier(fichier);
        OcrJobQueue.NouveauJob n = new OcrJobQueue.NouveauJob(doc, version, fichier, "application/pdf", "fra+ara", Instant.now());
        UUID id = file.enfiler(n);
        assertEquals(id, file.enfiler(n), "un dépôt rejoué ne crée pas de second job");
        OcrJob j = job(id);
        assertEquals(StatutOcr.EN_ATTENTE_OCR, j.statut());
        assertEquals("fra+ara", j.langue());
        assertEquals(0, j.tentatives());
        assertEquals(1, file.profondeur());
        assertEquals(Map.of(version, StatutOcr.EN_ATTENTE_OCR), file.statutsParVersion(List.of(version)));
    }

    @Test
    @DisplayName("Les contraintes refusent un statut ou une langue hors dossier")
    void contraintes() {
        UUID id = enfiler();
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ocr_job SET statut = 'TERMINE' WHERE id = ?", id));
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ocr_job SET langue = 'eng' WHERE id = ?", id));
    }

    @Test
    @DisplayName("Réservation : EN_COURS_OCR sous bail, tentative comptée, job non re-réservable")
    void reservation() {
        UUID id = enfiler();
        List<OcrJob> r = file.reserver("w1", 5, BAIL);
        assertEquals(1, r.size());
        OcrJob j = r.get(0);
        assertEquals(id, j.id());
        assertEquals(StatutOcr.EN_COURS_OCR, j.statut());
        assertEquals("w1", j.verrouillePar());
        assertEquals(1, j.tentatives());
        assertNotNull(j.verrouilleJusquA());
        assertTrue(file.reserver("w2", 5, BAIL).isEmpty());
        assertTrue(file.prolonger(id, "w1", BAIL));
        assertFalse(file.prolonger(id, "w2", BAIL), "seul le titulaire du bail le prolonge");
        assertTrue(file.terminer(id, "w1", 12));
        assertEquals(StatutOcr.OCR_TERMINE, job(id).statut());
        assertEquals(12, job(id).nbPages());
        assertEquals(0, file.profondeur());
    }

    @Test
    @DisplayName("Concurrence : 6 workers sur 300 jobs, chaque job réservé une seule fois")
    void concurrenceSansDoublon() throws Exception {
        for (int i = 0; i < 300; i++) enfiler();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch depart = new CountDownLatch(1);
        List<UUID> reserves = Collections.synchronizedList(new ArrayList<>());
        List<Future<Integer>> resultats = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            String nom = "w" + w;
            resultats.add(pool.submit(() -> {
                depart.await();
                int n = 0;
                List<OcrJob> lot;
                while (!(lot = file.reserver(nom, 3, BAIL)).isEmpty()) {
                    for (OcrJob j : lot) {
                        reserves.add(j.id());
                        assertTrue(file.terminer(j.id(), nom, 1));
                        n++;
                    }
                }
                return n;
            }));
        }
        depart.countDown();
        int total = 0;
        Set<Integer> parWorker = new HashSet<>();
        for (Future<Integer> f : resultats) {
            int n = f.get(60, TimeUnit.SECONDS);
            total += n;
            parWorker.add(n);
        }
        pool.shutdown();
        assertEquals(300, total);
        assertEquals(300, reserves.size());
        assertEquals(300, new HashSet<>(reserves).size(), "aucun job réservé deux fois");
        assertEquals(300L, file.compterParStatut().get(StatutOcr.OCR_TERMINE));
    }

    @Test
    @DisplayName("SKIP LOCKED : une ligne verrouillée par une autre transaction est sautée, sans attente")
    void skipLocked() throws Exception {
        UUID verrouille = enfiler(Instant.now().minusSeconds(60));
        UUID libre = enfiler();
        try (Connection c = base.source().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT id FROM ocr_job WHERE id = '" + verrouille + "' FOR UPDATE");
                // Sans SKIP LOCKED, la réservation attendrait la fin de cette
                // transaction : le délai d'instruction la ferait échouer.
                jdbc.execute("SET statement_timeout = '3s'");
                List<OcrJob> r = file.reserver("w2", 5, BAIL);
                assertEquals(List.of(libre), r.stream().map(OcrJob::id).toList());
            } finally {
                c.rollback();
                jdbc.execute("RESET statement_timeout");
            }
        }
        assertEquals(verrouille, file.reserver("w3", 5, BAIL).get(0).id());
    }

    @Test
    @DisplayName("Reprises à 1, 5 puis 30 minutes, puis OCR_ECHEC avec son motif")
    void reprises() {
        UUID id = enfiler();
        long[] attendus = {60, 300, 1800};
        for (int essai = 0; essai < 3; essai++) {
            OcrJob j = file.reserver("w", 1, BAIL).get(0);
            assertEquals(essai + 1, j.tentatives());
            assertEquals(StatutOcr.EN_ATTENTE_OCR, file.echouer(id, "w", "DELAI_DEPASSE : page 3", false));
            long s = secondesAvantProchaineTentative(id);
            assertTrue(Math.abs(s - attendus[essai]) <= 2, "reprise " + (essai + 1) + " à " + s + " s");
            assertTrue(file.reserver("w", 1, BAIL).isEmpty(), "pas avant l'échéance");
            avancerHorloge(id);
        }
        file.reserver("w", 1, BAIL);
        assertEquals(StatutOcr.OCR_ECHEC, file.echouer(id, "w", "DELAI_DEPASSE : page 3", false));
        OcrJob fin = job(id);
        assertEquals(StatutOcr.OCR_ECHEC, fin.statut());
        assertEquals(4, fin.tentatives());
        assertEquals("DELAI_DEPASSE : page 3", fin.motifEchec());
        assertNotNull(fin.termineLe());
        assertTrue(file.reserver("w", 1, BAIL).isEmpty());
    }

    @Test
    @DisplayName("Échec définitif (PDF protégé) : OCR_ECHEC sans reprise")
    void echecDefinitif() {
        UUID id = enfiler();
        file.reserver("w", 1, BAIL);
        assertEquals(StatutOcr.OCR_ECHEC, file.echouer(id, "w", "PROTEGE_PAR_MOT_DE_PASSE : PDF protégé", true));
        assertEquals(1, job(id).tentatives());
    }

    @Test
    @DisplayName("Relance manuelle d'un job en échec, et seulement d'un job en échec")
    void relance() {
        UUID id = enfiler();
        file.reserver("w", 1, BAIL);
        assertFalse(file.relancer(id), "un job en cours ne se relance pas");
        file.echouer(id, "w", "FICHIER_CORROMPU : x", true);
        assertTrue(file.relancer(id));
        OcrJob j = job(id);
        assertEquals(StatutOcr.EN_ATTENTE_OCR, j.statut());
        assertEquals(0, j.tentatives());
        assertEquals(id, file.reserver("w", 1, BAIL).get(0).id());
    }

    @Test
    @DisplayName("Bail expiré (worker arrêté) : le job est repris ; l'ancien worker ne peut plus le clore")
    void bailExpire() {
        UUID id = enfiler();
        file.reserver("mort", 1, BAIL);
        jdbc.update("UPDATE ocr_job SET verrouille_jusqu_a = now() - interval '1 second' WHERE id = ?", id);
        OcrJob repris = file.reserver("vivant", 1, BAIL).get(0);
        assertEquals(id, repris.id());
        assertEquals(2, repris.tentatives(), "l'exécution interrompue compte comme une tentative");
        assertFalse(file.terminer(id, "mort", 1));
        assertFalse(file.prolonger(id, "mort", BAIL));
        assertTrue(file.terminer(id, "vivant", 1));
    }

    @Test
    @DisplayName("Bail expiré sur la dernière tentative : OCR_ECHEC, pas de boucle infinie")
    void bailExpireEpuise() {
        UUID id = enfiler();
        jdbc.update("UPDATE ocr_job SET statut = 'EN_COURS_OCR', tentatives = 4, verrouille_par = 'mort', "
                + "verrouille_jusqu_a = now() - interval '1 second' WHERE id = ?", id);
        assertTrue(file.reserver("w", 1, BAIL).isEmpty());
        assertEquals(StatutOcr.OCR_ECHEC, job(id).statut());
        assertTrue(job(id).motifEchec().startsWith("DELAI_DEPASSE"));
    }

    @Test
    @DisplayName("Ordre : le plus ancien dépôt d'abord ; âge du plus ancien dépôt en attente")
    void ordreEtAge() {
        Instant vieux = Instant.now().minus(Duration.ofHours(30));
        UUID recent = enfiler(Instant.now().minusSeconds(10));
        UUID ancien = enfiler(vieux);
        assertEquals(vieux.getEpochSecond(), file.plusAncienDepotEnAttente().orElseThrow().getEpochSecond());
        assertEquals(ancien, file.reserver("w", 1, BAIL).get(0).id());
        assertEquals(recent, file.reserver("w", 1, BAIL).get(0).id());
        assertEquals(2, file.lister(StatutOcr.EN_COURS_OCR, 10, 0).size());
    }

    private UUID enfilerReprise(Instant depose) {
        UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), fichier = UUID.randomUUID();
        base.document(doc, version);
        base.cleFichier(fichier);
        return file.enfiler(new OcrJobQueue.NouveauJob(doc, version, fichier, "application/pdf", "fra+ara", depose,
                PrioriteOcr.REPRISE));
    }

    @Test
    @DisplayName("R31 / D6 : un dépôt courant passe avant tout l'arriéré de la reprise, qui avance ensuite")
    void fluxCourantAvantReprise() {
        // Arriéré de reprise enfilé bien avant le dépôt courant (et une tentative reprogrammée échue).
        List<UUID> reprise = new ArrayList<>();
        for (int i = 0; i < 5; i++) reprise.add(enfilerReprise(Instant.now().minus(Duration.ofDays(10 - i))));
        UUID courant = enfiler(Instant.now());
        assertEquals(1, jdbc.queryForObject("SELECT priorite FROM ocr_job WHERE id = ?", Integer.class, reprise.get(0)));
        assertEquals(0, jdbc.queryForObject("SELECT priorite FROM ocr_job WHERE id = ?", Integer.class, courant));

        // Un seul worker libre : il prend le dépôt courant, pas le plus ancien job de reprise.
        assertEquals(courant, file.reserver("w", 1, BAIL).get(0).id());
        // Âge de la file mesuré sur le flux seul (D6) : l'arriéré de reprise ne le fausse pas.
        assertTrue(file.plusAncienDepotEnAttente().isPresent());
        assertTrue(file.terminer(courant, "w", 1));
        assertTrue(file.plusAncienDepotEnAttente().isEmpty(), "aucun dépôt courant en attente");

        // Flux vide : la reprise avance, dans son ordre de dépôt.
        List<UUID> servis = new ArrayList<>();
        // Un par un : l'ordre des lignes rendues par une même réservation n'est pas garanti.
        servis.add(file.reserver("w", 1, BAIL).get(0).id());
        servis.add(file.reserver("w", 1, BAIL).get(0).id());
        assertEquals(reprise.subList(0, 2), servis);
        // Un nouveau dépôt courant arrivé entre-temps repasse devant le reste de la reprise.
        UUID suivant = enfiler(Instant.now());
        assertEquals(suivant, file.reserver("w", 1, BAIL).get(0).id());
        // Contrainte : priorité hors liste refusée.
        assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ocr_job SET priorite = 7 WHERE id = ?", suivant));
    }
}
