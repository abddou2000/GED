package com.ipt.ged.cycledevie;

import com.ipt.ged.document.evenement.DocumentArchive;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.workspace.WorkSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Archivage d'un dossier entier (revue client D10, §12.6) : drapeau posé sur
 * le dossier, job traité par tranches (ici 2 documents par tranche),
 * progression, reprise après interruption, annulation entre deux tranches,
 * rapport, un événement d'audit par document archivé.
 */
@RecordApplicationEvents
class ArchivageDossierApiTest extends BaseCycleDeVieApiTest {

    /** Demande l'annulation du job quand le N-ième document est archivé (simule un clic entre deux tranches). */
    static final AtomicReference<UUID> ANNULER_JOB = new AtomicReference<>();
    static final AtomicInteger ANNULER_APRES = new AtomicInteger(-1);

    @TestConfiguration
    static class Annulation {
        @Bean
        Object annulationEntreTranches(JdbcTemplate jdbc) {
            return new Object() {
                @EventListener
                public void surArchive(DocumentArchive e) {
                    if (e.jobId() != null && e.jobId().equals(ANNULER_JOB.get()) && ANNULER_APRES.decrementAndGet() == 0) {
                        jdbc.update("UPDATE job_archivage SET annulation_demandee = true WHERE id = ?", e.jobId());
                    }
                }
            };
        }
    }

    @Autowired private ArchivageDossiers archivage;
    @Autowired private ProprietesCycleDeVie proprietes;
    @Autowired private ApplicationEvents evenements;

    private WorkSpace racine, sous;
    private UUID typeRacine, typeSous;
    private int trancheInitiale;

    @BeforeEach
    void preparer() {
        trancheInitiale = proprietes.getArchivage().getTranche();
        proprietes.getArchivage().setTranche(2);
        racine = espace("Marchés " + suffixe, null);
        sous = espace("Lot 1 " + suffixe, racine);
        typeRacine = type(racine, "pdf,png");
        typeSous = type(sous, "pdf,png");
        ANNULER_JOB.set(null);
        ANNULER_APRES.set(-1);
    }

    @AfterEach
    void retablir() {
        proprietes.getArchivage().setTranche(trancheInitiale);
    }

    /** Traite tous les jobs disponibles (d'éventuels jobs d'autres tests passent avant). */
    private void vider() {
        int n = 0;
        while (archivage.traiterUnJob()) n++;
        assertTrue(n > 0, "au moins un job traité");
    }

    private ArchivageDossiers.Job job(UUID id) {
        return archivage.job(id).orElseThrow();
    }

    private UUID demander(UUID dossier) throws Exception {
        return UUID.fromString(json(mvc.perform(post("/api/v1/archivage/dossiers/" + dossier))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.etat").value("EN_ATTENTE"))).get("id").asText());
    }

    @Test
    @DisplayName("Dossier et sous-dossier : 5 documents actifs archivés en 3 tranches, corbeille exclue, dépôt refusé")
    void dossierEntier() throws Exception {
        UUID a = deposer(typeRacine, "a", "a.pdf", "application/pdf", Echantillons.pdf());
        UUID b = deposer(typeRacine, "b", "b.png", "image/png", Echantillons.image("png"));
        UUID c = deposer(typeSous, "c", "c.pdf", "application/pdf", Echantillons.pdf());
        UUID d = deposer(typeSous, "d", "d.pdf", "application/pdf", Echantillons.pdf());
        UUID e = deposer(typeSous, "e", "e.pdf", "application/pdf", Echantillons.pdf());
        UUID supprime = deposer(typeRacine, "x", "x.pdf", "application/pdf", Echantillons.pdf());
        mvc.perform(delete("/api/v1/documents/" + supprime)).andExpect(status().isNoContent());
        UUID dejaArchive = deposer(typeRacine, "y", "y.pdf", "application/pdf", Echantillons.pdf());
        mvc.perform(post("/api/v1/documents/" + dejaArchive + "/archivage")).andExpect(status().isOk());

        UUID jobId = demander(racine.getId());
        assertEquals(5, job(jobId).total(), "sélection figée : actifs hors corbeille, sous-dossiers compris");
        // Drapeau posé : plus aucun dépôt dans le dossier ni dans ses sous-dossiers (Q7).
        mvc.perform(multipart("/api/v1/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "z.pdf", "application/pdf", Echantillons.pdf()))
                        .param("typeDocumentId", typeSous.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(ErreurCycleDeVie.DOSSIER_ARCHIVE));
        // Un seul job à la fois par dossier.
        mvc.perform(post("/api/v1/archivage/dossiers/" + racine.getId())).andExpect(status().isConflict());

        vider();
        ArchivageDossiers.Job fin = job(jobId);
        assertEquals("TERMINE", fin.etat());
        assertEquals(5, fin.traites());
        assertEquals(5, fin.archives());
        assertEquals(0, fin.echecs());
        assertNotNull(fin.termineLe());
        for (UUID doc : List.of(a, b, c, d, e)) {
            assertEquals("ARCHIVE", jdbc.queryForObject("SELECT statut_conservation FROM document WHERE id = ?",
                    String.class, doc));
        }
        assertEquals("ACTIF", jdbc.queryForObject("SELECT statut_conservation FROM document WHERE id = ?",
                String.class, supprime));
        assertEquals(5, evenements.stream(DocumentArchive.class).filter(x -> jobId.equals(x.jobId())).count(),
                "un événement d'audit par document archivé");

        mvc.perform(get("/api/v1/archivage/jobs/" + jobId + "/elements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].resultat").value("ARCHIVE"));
        mvc.perform(get("/api/v1/archivage/jobs").param("dossierId", racine.getId().toString()))
                .andExpect(jsonPath("$[0].id").value(jobId.toString()));
        assertFalse(archivage.traiterUnJob(), "plus rien à traiter");

        // Drapeau retiré : dépôt de nouveau accepté, documents toujours archivés.
        mvc.perform(delete("/api/v1/archivage/dossiers/" + racine.getId())).andExpect(status().isNoContent());
        deposer(typeSous, "z", "z.pdf", "application/pdf", Echantillons.pdf());
        assertEquals("ARCHIVE", jdbc.queryForObject("SELECT statut_conservation FROM document WHERE id = ?",
                String.class, c));
    }

    @Test
    @DisplayName("Annulation entre deux tranches : les documents archivés le restent, le drapeau est retiré")
    void annulationEntreTranches() throws Exception {
        for (int i = 0; i < 5; i++) deposer(typeRacine, "n" + i, "n" + i + ".pdf", "application/pdf", Echantillons.pdf());
        UUID jobId = demander(racine.getId());
        ANNULER_JOB.set(jobId);
        ANNULER_APRES.set(2); // pendant la première tranche

        vider();
        ArchivageDossiers.Job fin = job(jobId);
        assertEquals("ANNULE", fin.etat());
        assertEquals(2, fin.archives(), "la tranche en cours s'achève, la suivante n'est pas commencée");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM job_archivage_element WHERE job_archivage_id = ? "
                + "AND resultat IS NULL", Integer.class, jobId));
        assertEquals("ACTIF", jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, racine.getId()));
        mvc.perform(post("/api/v1/archivage/jobs/" + jobId + "/annulation")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Annulation avant tout traitement : job annulé sur-le-champ")
    void annulationImmediate() throws Exception {
        deposer(typeRacine, "n", "n.pdf", "application/pdf", Echantillons.pdf());
        UUID jobId = demander(racine.getId());
        mvc.perform(post("/api/v1/archivage/jobs/" + jobId + "/annulation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.etat").value("ANNULE"));
        while (archivage.traiterUnJob()) {
            // d'éventuels jobs d'autres tests
        }
        assertEquals("ANNULE", job(jobId).etat());
        assertEquals(0, job(jobId).traites());
        assertEquals("ACTIF", jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, racine.getId()));
    }

    @Test
    @DisplayName("Reprise après interruption : bail expiré, seuls les documents non traités sont repris")
    void reprise() throws Exception {
        UUID a = deposer(typeRacine, "a", "a.pdf", "application/pdf", Echantillons.pdf());
        deposer(typeRacine, "b", "b.pdf", "application/pdf", Echantillons.pdf());
        deposer(typeRacine, "c", "c.pdf", "application/pdf", Echantillons.pdf());
        UUID jobId = demander(racine.getId());
        // Instance tombée après avoir traité le premier document, bail expiré.
        jdbc.update("UPDATE job_archivage_element SET resultat = 'ARCHIVE', traite_le = now() "
                + "WHERE job_archivage_id = ? AND document_id = ?", jobId, a);
        jdbc.update("UPDATE document SET statut_conservation = 'ARCHIVE', archive_le = now() WHERE id = ?", a);
        jdbc.update("UPDATE job_archivage SET etat = 'EN_COURS', traites = 1, archives = 1, verrouille_par = 'autre', "
                + "verrouille_jusqu_a = now() - interval '1 minute' WHERE id = ?", jobId);

        vider();
        ArchivageDossiers.Job fin = job(jobId);
        assertEquals("TERMINE", fin.etat());
        assertEquals(3, fin.traites());
        assertEquals(3, fin.archives());
        assertEquals(2, evenements.stream(DocumentArchive.class).filter(x -> jobId.equals(x.jobId())).count(),
                "le document déjà traité n'est pas repris");
    }

    @Test
    @DisplayName("Job d'une autre instance au bail valide : pas repris")
    void bailValide() throws Exception {
        deposer(typeRacine, "a", "a.pdf", "application/pdf", Echantillons.pdf());
        UUID jobId = demander(racine.getId());
        jdbc.update("UPDATE job_archivage SET etat = 'EN_COURS', verrouille_par = 'autre', "
                + "verrouille_jusqu_a = now() + interval '10 minutes' WHERE id = ?", jobId);
        // D'autres jobs en attente (d'autres tests) peuvent être traités ; celui-ci ne l'est pas.
        while (archivage.traiterUnJob()) {
            // vider la file
        }
        assertEquals("EN_COURS", job(jobId).etat());
        assertEquals(0, job(jobId).traites());
        jdbc.update("UPDATE job_archivage SET etat = 'ANNULE', verrouille_jusqu_a = NULL WHERE id = ?", jobId);
    }
}
