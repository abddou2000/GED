package com.ipt.ged.typedocument.retypage;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.modele.EvenementModeleDocument;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-typologisation d'un lot (§12.7) : chaque document dans sa transaction,
 * d'où une classe NON transactionnelle ; le jeu d'essai est retiré par
 * identifiants exacts à la fin.
 */
// Exécution synchrone : les événements d'audit sont publiés dans le fil du
// test, où @RecordApplicationEvents les enregistre.
@SpringBootTest(properties = "ged.retypage.asynchrone=false")
@ActiveProfiles("test")
@RecordApplicationEvents
@WithUserDetails(Comptes.ADMIN)
class RetypageTest {

    @Autowired private ServiceRetypage service;
    @Autowired private JeuDroits jeu;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UploadDocumentRepository documents;
    @Autowired private ApplicationEvents evenements;

    private final List<UUID> docs = new ArrayList<>(), types = new ArrayList<>(), noeuds = new ArrayList<>();
    private final List<UUID> jobs = new ArrayList<>(), indices = new ArrayList<>(), plans = new ArrayList<>();

    @AfterEach
    void nettoyage() {
        jobs.forEach(id -> jdbc.update("DELETE FROM job_retypage WHERE id = ?", id));
        docs.forEach(id -> {
            jdbc.update("DELETE FROM version_document WHERE document_id = ?", id);
            jdbc.update("DELETE FROM document WHERE id = ?", id);
        });
        types.forEach(id -> jdbc.update("DELETE FROM type_document WHERE id = ?", id));
        plans.forEach(id -> {
            jdbc.update("DELETE FROM plan_indexation_version WHERE plan_indexation_id = ?", id);
            jdbc.update("DELETE FROM plan_index WHERE plan_indexation_id = ?", id);
            jdbc.update("DELETE FROM plan_indexation WHERE id = ?", id);
        });
        indices.forEach(id -> jdbc.update("DELETE FROM index_def WHERE id = ?", id));
        noeuds.forEach(id -> {
            UUID wf = jdbc.queryForObject("SELECT workflow_ged_id FROM noeud WHERE id = ?", UUID.class, id);
            jdbc.update("DELETE FROM noeud WHERE id = ?", id);
            jdbc.update("DELETE FROM workflow_ged WHERE id = ?", wf);
        });
    }

    private UUID doc(UUID type, Map<String, Object> meta, boolean verrouille) {
        UUID id = jeu.document("R-" + UUID.randomUUID().toString().substring(0, 6), type, Confidentialite.PUBLIC, null);
        docs.add(id);
        var d = documents.findById(id).orElseThrow();
        d.setMetadonnees(new HashMap<>(meta));
        if (verrouille) d.verrouiller(null, "en revue");
        documents.saveAndFlush(d);
        return id;
    }

    @Test
    @DisplayName("Lot : correspondance ancien champ → nouveau, déplacement vers le dossier cible, échecs isolés, rapport, audit par document")
    void retypageDUnLot() {
        UUID source = jeu.noeud("Source", null), cible = jeu.noeud("Cible", null);
        noeuds.addAll(List.of(source, cible));
        UUID tSource = jeu.type(source, Confidentialite.PUBLIC), tCible = jeu.type(cible, Confidentialite.PUBLIC);
        types.addAll(List.of(tSource, tCible));
        IndexField ancienMontant = jeu.index("ANC_MONTANT", IndexFieldType.TEXTE, false, null);
        IndexField ancienNote = jeu.index("ANC_NOTE", IndexFieldType.TEXTE, false, null);
        IndexField montant = jeu.index("MONTANT_HT", IndexFieldType.NOMBRE, true, null);
        indices.addAll(List.of(ancienMontant.getId(), ancienNote.getId(), montant.getId()));
        plans.add(jeu.planPour(tSource, ancienMontant, ancienNote).getId());
        plans.add(jeu.planPour(tCible, montant).getId());

        UUID ok = doc(tSource, Map.of(ancienMontant.getCode(), "120.5", ancienNote.getCode(), "à perdre"), false);
        UUID invalide = doc(tSource, Map.of(ancienMontant.getCode(), "cent"), false);
        UUID verrouille = doc(tSource, Map.of(ancienMontant.getCode(), "3"), true);

        JobRetypage job = service.lancer(new ServiceRetypage.Demande(tSource, tCible,
                Map.of(ancienMontant.getCode().toLowerCase(), montant.getCode()), null));
        jobs.add(job.getId());
        assertEquals(3, job.getTotal());
        service.executer(job.getId());
        JobRetypage fini = attendreFin(job.getId());
        assertEquals(JobRetypage.Statut.TERMINE, fini.getStatut());
        assertEquals(3, fini.getTraites());
        assertEquals(1, fini.getReussis());
        assertEquals(2, fini.getEchecs());
        Map<String, Object> ligneOk = fini.getRapport().stream()
                .filter(l -> ok.toString().equals(String.valueOf(l.get("documentId")))).findFirst().orElseThrow();
        assertEquals("SUCCES", ligneOk.get("resultat"));
        assertEquals(List.of(ancienNote.getCode()), ligneOk.get("champsPerdus"));
        assertTrue(fini.getRapport().stream().anyMatch(l -> invalide.toString().equals(String.valueOf(l.get("documentId")))
                && "METADONNEES_INVALIDES".equals(l.get("motif"))));
        assertTrue(fini.getRapport().stream().anyMatch(l -> verrouille.toString().equals(String.valueOf(l.get("documentId")))
                && "DOCUMENT_VERROUILLE".equals(l.get("motif"))));

        var d = documents.findById(ok).orElseThrow();
        assertEquals(tCible, d.getTypeDocument().getId());
        assertEquals(cible, d.getWorkspace().getId());
        assertEquals(Map.of(montant.getCode(), new java.math.BigDecimal("120.5")).toString(),
                d.getMetadonnees().toString());
        assertEquals(tSource, documents.findById(invalide).orElseThrow().getTypeDocument().getId(), "échec isolé");
        assertEquals(1, evenements.stream(EvenementModeleDocument.class)
                .filter(e -> e.action().equals(EvenementModeleDocument.DOCUMENT_RETYPE)).count());

        // Relancer le même travail ne retraite rien.
        service.executer(job.getId());
        assertEquals(3, service.consulter(job.getId()).getTraites());
    }

    private JobRetypage attendreFin(UUID id) {
        long limite = System.currentTimeMillis() + 30_000;
        JobRetypage j = service.consulter(id);
        while (j.getStatut() != JobRetypage.Statut.TERMINE && j.getStatut() != JobRetypage.Statut.ECHEC
                && System.currentTimeMillis() < limite) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            j = service.consulter(id);
        }
        return j;
    }
}
