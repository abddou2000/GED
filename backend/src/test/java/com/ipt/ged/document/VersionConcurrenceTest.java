package com.ipt.ged.document;

import com.ipt.ged.support.Comptes;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Point 1 de l'audit — <b>deux dépôts de version simultanés cassaient le
 * document définitivement</b>.
 *
 * <p>Ce que ces tests prouvent :
 * <ol>
 *   <li>six dépôts de version lancés en parallèle laissent le document avec
 *       <b>exactement une</b> version principale ; aucun appel ne remonte
 *       d'erreur non maîtrisée (le seul échec toléré est un conflit d'accès,
 *       rendu en 409), et le document reste utilisable après la rafale ;</li>
 *   <li>un document <b>déjà abîmé</b> — deux drapeaux « principale » posés
 *       directement en base, comme la course les laissait — se répare par
 *       l'API au lieu d'échouer pour toujours.</li>
 * </ol>
 *
 * <p>La classe n'est volontairement <b>pas</b> {@code @Transactional} : la
 * propriété testée est une propriété de concurrence entre transactions
 * distinctes, elle disparaîtrait dans une transaction de test unique. Le jeu
 * d'essai est donc nettoyé explicitement.
 *
 * <p>Depuis le lot E3 chaque appel du service est soumis aux droits : les
 * appels partent sous l'identité de l'Administrateur, propagée aux fils de
 * la rafale.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class VersionConcurrenceTest {

    @Autowired private DocumentService service;
    @Autowired private UploadDocumentRepository documentRepository;
    @Autowired private DocumentVersionRepository versionRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private EmployeRepository employeRepository;

    private UUID typeId, workspaceId, workflowId;
    private final List<UUID> documentsCrees = new ArrayList<>();

    @BeforeEach
    void setup() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        // Circuit SANS étape : aucun document de ce test n'entre en signature,
        // ce qui garde le nettoyage simple et le test centré sur les versions.
        WorkflowGed wf = workflowRepository.save(new WorkflowGed("WF concurrence"));
        workflowId = wf.getId();

        WorkSpace w = new WorkSpace("Concurrence", "WS-CONC");
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        workspaceId = workspaceRepository.save(w).getId();

        TypeDocument type = new TypeDocument("TD-CONC", "Facture");
        type.setDescription("desc");
        type.setWorkspace(w);
        type.setTypeAutorise("pdf");
        type.setTailleMaxMo(5);
        typeId = typeRepository.save(type).getId();
    }

    @AfterEach
    void nettoyage() {
        documentsCrees.forEach(id -> documentRepository.findById(id).ifPresent(documentRepository::delete));
        documentsCrees.clear();
        typeRepository.deleteById(typeId);
        workspaceRepository.deleteById(workspaceId);
        workflowRepository.deleteById(workflowId);
    }

    private MockMultipartFile fichier(String nom) {
        return new MockMultipartFile("file", nom, "application/pdf", com.ipt.ged.support.Pdfs.pdf(nom));
    }

    private UUID deposer(String nom) {
        UUID id = service.upload(fichier(nom), nom, typeId, null, null, null).id();
        documentsCrees.add(id);
        return id;
    }

    private List<DocumentVersion> principales(UUID documentId) {
        return versionRepository.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId);
    }

    @Test
    @DisplayName("1a. Six dépôts de version simultanés : une seule version principale, aucun 500, document toujours utilisable")
    void depotsSimultanes() throws Exception {
        UUID docId = deposer("depart.pdf");

        int concurrents = 6;
        ExecutorService pool = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(concurrents));
        CountDownLatch top = new CountDownLatch(1);
        List<Future<String>> lances = new ArrayList<>();
        for (int i = 0; i < concurrents; i++) {
            final int rang = i;
            lances.add(pool.submit(() -> {
                top.await();
                try {
                    service.ajouterVersion(docId, fichier("v" + rang + ".pdf"), "version " + rang);
                    return "OK";
                } catch (ConcurrencyFailureException e) {
                    // Verrou non obtenu : conflit d'accès, rendu en 409 par le
                    // gestionnaire global. L'appelant peut rejouer.
                    return "CONFLIT";
                } catch (RuntimeException e) {
                    return "ERREUR:" + e.getClass().getSimpleName();
                }
            }));
        }
        top.countDown();

        List<String> resultats = new ArrayList<>();
        for (Future<String> f : lances) resultats.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        // Avant correction : plusieurs 200 et un 500 (NonUniqueResultException).
        assertTrue(resultats.contains("OK"), "aucun dépôt n'a abouti : " + resultats);
        assertTrue(resultats.stream().allMatch(r -> r.equals("OK") || r.equals("CONFLIT")),
                "une requête a produit une erreur non maîtrisée (500) : " + resultats);

        // L'invariant : une seule version principale, quoi qu'il arrive.
        assertEquals(1, principales(docId).size(),
                "le document porte plusieurs versions principales : état irrécupérable");

        // Et le document reste exploitable : ni lecture ni écriture ne sont bloquées.
        assertNotNull(service.get(docId));
        service.ajouterVersion(docId, fichier("apres.pdf"), "après la rafale");
        assertEquals(1, principales(docId).size());
    }

    @Test
    @DisplayName("1b. Un document déjà abîmé (deux versions principales) se répare par l'API au lieu d'échouer pour toujours")
    void etatAbimeReparable() {
        UUID docId = deposer("abime.pdf");
        service.ajouterVersion(docId, fichier("v2.pdf"), "deuxième");

        // On recrée à la main l'état que la course produisait : DEUX principales.
        List<DocumentVersion> toutes = versionRepository.findByDocumentIdOrderByIdDesc(docId);
        assertEquals(2, toutes.size());
        toutes.forEach(v -> v.setPrincipale(true));
        versionRepository.saveAll(toutes);
        assertEquals(2, principales(docId).size(), "l'état abîmé n'a pas été reproduit");

        // Avant correction : findByDocumentIdAndPrincipaleTrue renvoyait un
        // Optional et levait NonUniqueResultException — 500 définitif, aucune
        // route ne permettant de remettre le drapeau d'aplomb.
        assertDoesNotThrow(() -> service.get(docId));
        assertDoesNotThrow(() -> service.ajouterVersion(docId, fichier("reparation.pdf"), "réparation"));
        assertEquals(1, principales(docId).size(), "l'état abîmé n'a pas été réparé");
    }
}
