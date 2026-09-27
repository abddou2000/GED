package com.ipt.ged.cycledevie;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.DocumentPurge;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.cles.DepotClesFichier;
import com.ipt.ged.fichier.previsualisation.ServicePrevisualisation;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Purge définitive d'un document (§12.5) : <b>sur un document déjà en
 * corbeille uniquement</b>, jamais automatique (P4), permission {@code Purger}.
 *
 * <p>Dans une seule transaction : suppression des lignes métier (document,
 * versions, valeurs d'index, étiquettes, signatures, texte indexé et jobs OCR
 * par cascade, copies de conservation, éléments de job d'archivage) et
 * <b>destruction des clés de fichier (DEK)</b> — c'est elle qui rend les
 * fichiers définitivement illisibles, sauvegardes comprises (destruction
 * cryptographique, §6.1.2). Après validation, les fichiers chiffrés sont
 * effacés du stockage et les aperçus en cache invalidés ; un échec à ce stade
 * ne laisse qu'un bloc indéchiffrable. Le journal d'audit est conservé :
 * l'événement {@code DOCUMENT_PURGE} y est écrit dans la même transaction.
 *
 * <p>Les rattachements multiples (table {@code document_rattachement}, lot E3)
 * sont à supprimer avec les autres lignes métier à la fusion avec E3 (ou par
 * la cascade de leur clé étrangère).
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private final UploadDocumentRepository documents;
    private final AutorisationsCycleDeVie autorisations;
    private final JdbcTemplate jdbc;
    private final DepotClesFichier cles;
    private final StockageChiffre stockage;
    private final ObjectProvider<ServicePrevisualisation> apercus;
    private final EntityManager em;
    private final ApplicationEventPublisher evenements;

    public PurgeService(UploadDocumentRepository documents, AutorisationsCycleDeVie autorisations, JdbcTemplate jdbc,
                        DepotClesFichier cles, StockageChiffre stockage, ObjectProvider<ServicePrevisualisation> apercus,
                        EntityManager em, ApplicationEventPublisher evenements) {
        this.documents = documents;
        this.autorisations = autorisations;
        this.jdbc = jdbc;
        this.cles = cles;
        this.stockage = stockage;
        this.apercus = apercus;
        this.em = em;
        this.evenements = evenements;
    }

    /** Purge un document en corbeille ; 409 s'il n'y est pas, 403 sans la permission. */
    @Transactional
    public void purger(UUID documentId) {
        UploadDocument d = documents.findByIdPourEcriture(documentId)
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Document " + documentId));
        if (!autorisations.peutPurger(documentId)) {
            throw ErreurCycleDeVie.permission("Purger");
        }
        if (!d.isDeleted()) {
            throw ErreurCycleDeVie.conflit(ErreurCycleDeVie.DOCUMENT_NON_SUPPRIME,
                    "Seul un document placé en corbeille peut être purgé définitivement.");
        }
        String nom = d.getName();
        UUID versionCourante = d.getVersions().stream().filter(v -> v.isPrincipale()).map(v -> v.getId())
                .findFirst().orElse(null);
        // La suite passe en SQL : l'entité ne doit plus être écrite par Hibernate.
        em.flush();
        em.detach(d);

        List<UUID> versions = jdbc.queryForList(
                "SELECT id FROM version_document WHERE document_id = ?", UUID.class, documentId);
        Set<UUID> fichiers = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT cle_fichier_id FROM version_document WHERE document_id = ? AND cle_fichier_id IS NOT NULL",
                UUID.class, documentId));
        fichiers.addAll(jdbc.queryForList(
                "SELECT c.cle_fichier_id FROM copie_conservation c JOIN version_document v ON v.id = c.version_id "
                        + "WHERE v.document_id = ? AND c.cle_fichier_id IS NOT NULL", UUID.class, documentId));

        evenements.publishEvent(new DocumentPurge(documentId, versionCourante, Acteur.courant(), Instant.now(),
                nom, versions.size(), fichiers.size()));

        jdbc.update("DELETE FROM copie_conservation WHERE version_id IN "
                + "(SELECT id FROM version_document WHERE document_id = ?)", documentId);
        jdbc.update("DELETE FROM workflow_ged_signature WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_index_valeur WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document_etiquette WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM version_document WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM document WHERE id = ?", documentId);

        // Destruction cryptographique dans la transaction : sans sa DEK, un
        // fichier est illisible, y compris dans les sauvegardes.
        for (UUID f : fichiers) {
            cles.supprimer(f);
        }
        effacerApresValidation(new ArrayList<>(fichiers));
    }

    /** Purge plusieurs documents, chacun avec ses contrôles ; tout ou rien. */
    @Transactional
    public void purger(List<UUID> documentIds) {
        for (UUID id : new LinkedHashSet<>(documentIds)) {
            purger(id);
        }
    }

    private void effacerApresValidation(List<UUID> fichiers) {
        if (fichiers.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ServicePrevisualisation apercu = apercus.getIfAvailable();
                for (UUID f : fichiers) {
                    try {
                        stockage.detruire(f);
                        if (apercu != null) apercu.invaliderCache(f);
                    } catch (RuntimeException e) {
                        log.warn("Purge : fichier {} non effacé (sa clé est détruite, il est illisible)", f, e);
                    }
                }
            }
        });
    }
}
