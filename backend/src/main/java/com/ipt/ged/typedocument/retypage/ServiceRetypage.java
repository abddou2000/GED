package com.ipt.ged.typedocument.retypage;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ConflitAutorisationException;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.document.GardeEcriture;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.document.modele.EvenementModeleDocument;
import com.ipt.ged.document.modele.ServiceModeleDocument;
import com.ipt.ged.planindexation.metamodele.MetadonneesInvalidesException;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Re-typologisation d'un lot (dossier technique §12.7, gouvernance) : un
 * traitement de fond change le type d'un ensemble de documents en appliquant
 * une table de correspondance ancien champ → nouveau champ.
 *
 * <p>Par document, dans SA transaction (un échec n'annule pas les autres) :
 * refus s'il est verrouillé ou archivé ; métadonnées transposées (champ
 * renommé par la correspondance, champ de même code conservé, champ sans
 * équivalent signalé comme perdu) puis validées contre la version en vigueur
 * du plan cible ; emplacement principal déplacé vers le dossier du type cible ;
 * échéance de conservation recalculée par la base ; un événement d'audit
 * {@code DOCUMENT_RETYPE} avec avant / après. Le rapport liste chaque document.
 *
 * <p>Lancement réservé à {@code GERER_REFERENTIELS} (opération d'administration
 * des référentiels) ; aucun contrôle de droit par document.
 */
@Service
public class ServiceRetypage {

    private static final Logger log = LoggerFactory.getLogger(ServiceRetypage.class);

    /** Réponse de création et de consultation d'un travail. */
    public record Demande(UUID sourceTypeDocumentId, UUID cibleTypeDocumentId, Map<String, String> correspondance,
                          List<UUID> documentIds) {}

    private final JobRetypageRepository jobs;
    private final TypeDocumentRepository types;
    private final UploadDocumentRepository documents;
    private final ServiceModeleDocument modele;
    private final GardeEcriture garde;
    private final ControleAcces controle;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher evenements;
    private final TaskExecutor executeur;
    private final TransactionTemplate parDocument;

    /**
     * Lancement en arrière-plan après validation de la création (défaut). Faux :
     * l'appelant exécute lui-même le travail ({@link #executer}) — tests.
     */
    @org.springframework.beans.factory.annotation.Value("${ged.retypage.asynchrone:true}")
    private boolean asynchrone = true;

    public ServiceRetypage(JobRetypageRepository jobs, TypeDocumentRepository types, UploadDocumentRepository documents,
                           ServiceModeleDocument modele, GardeEcriture garde, ControleAcces controle, JdbcTemplate jdbc,
                           ApplicationEventPublisher evenements, TaskExecutor executeur,
                           PlatformTransactionManager transactions) {
        this.jobs = jobs;
        this.types = types;
        this.documents = documents;
        this.modele = modele;
        this.garde = garde;
        this.controle = controle;
        this.jdbc = jdbc;
        this.evenements = evenements;
        this.executeur = executeur;
        this.parDocument = new TransactionTemplate(transactions);
        this.parDocument.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Crée le travail et le lance en arrière-plan une fois la création validée.
     * Les documents sont figés à la création (sélection, ou tous ceux du type).
     */
    @Transactional
    public JobRetypage lancer(Demande d) {
        controle.exigerAdministration(CodePermission.GERER_REFERENTIELS);
        if (d.sourceTypeDocumentId() == null || d.cibleTypeDocumentId() == null) {
            throw new IllegalArgumentException("Types source et cible obligatoires.");
        }
        if (d.sourceTypeDocumentId().equals(d.cibleTypeDocumentId())) {
            throw new IllegalArgumentException("Le type cible doit différer du type source.");
        }
        TypeDocument source = types.findById(d.sourceTypeDocumentId())
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + d.sourceTypeDocumentId()));
        TypeDocument cible = types.findById(d.cibleTypeDocumentId())
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + d.cibleTypeDocumentId()));
        com.ipt.ged.document.ContraintesDepot.validerTypeVivant(cible);

        List<UUID> selection = d.documentIds() == null || d.documentIds().isEmpty()
                ? jdbc.queryForList("SELECT id FROM document WHERE type_document_id = ? AND supprime = false ORDER BY id",
                        UUID.class, source.getId())
                : jdbc.queryForList("SELECT id FROM document WHERE type_document_id = ? AND supprime = false"
                        + " AND id = ANY (CAST(? AS uuid[])) ORDER BY id", UUID.class, source.getId(),
                        "{" + String.join(",", d.documentIds().stream().map(UUID::toString).toList()) + "}");

        JobRetypage job = new JobRetypage();
        job.setSourceTypeDocumentId(source.getId());
        job.setCibleTypeDocumentId(cible.getId());
        Map<String, String> correspondance = new LinkedHashMap<>();
        if (d.correspondance() != null) {
            d.correspondance().forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) correspondance.put(k.trim(), v.trim());
            });
        }
        job.setCorrespondance(correspondance);
        job.setSelection(selection);
        job.setTotal(selection.size());
        job.setDemandeurUtilisateurId(ActeurCourant.utilisateurId());
        JobRetypage enregistre = jobs.saveAndFlush(job);

        UUID id = enregistre.getId();
        if (asynchrone && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeur.execute(() -> executer(id));
                }
            });
        }
        return enregistre;
    }

    @Transactional(readOnly = true)
    public JobRetypage consulter(UUID id) {
        controle.exigerAdministration(CodePermission.GERER_REFERENTIELS);
        return jobs.findById(id).orElseThrow(() -> new EntityNotFoundException("Travail introuvable : " + id));
    }

    @Transactional(readOnly = true)
    public List<JobRetypage> derniers() {
        controle.exigerAdministration(CodePermission.GERER_REFERENTIELS);
        return jobs.findTop50ByOrderByCreeLeDesc();
    }

    /**
     * Exécute le travail, un document par transaction ; les compteurs et le
     * rapport sont écrits après chaque document (suivi de progression, reprise :
     * un document déjà retypé n'est plus du type source et se signale ignoré).
     */
    public void executer(UUID jobId) {
        // Prise du travail atomique : une seule exécution, même si deux
        // déclenchements se croisent (lancement asynchrone et reprise).
        Integer pris = parDocument.execute(s -> jdbc.update("UPDATE job_retypage SET statut = 'EN_COURS',"
                + " debut_le = now() WHERE id = ? AND statut = 'EN_ATTENTE'", jobId));
        if (pris == null || pris == 0) return;
        JobRetypage job = parDocument.execute(s -> jobs.findById(jobId).orElseThrow());
        try {
            for (UUID documentId : job.getSelection()) {
                Map<String, Object> ligne = traiter(job, documentId);
                parDocument.executeWithoutResult(s -> {
                    JobRetypage j = jobs.findById(jobId).orElseThrow();
                    j.setTraites(j.getTraites() + 1);
                    if ("SUCCES".equals(ligne.get("resultat"))) j.setReussis(j.getReussis() + 1);
                    else j.setEchecs(j.getEchecs() + 1);
                    List<Map<String, Object>> rapport = new ArrayList<>(j.getRapport());
                    rapport.add(ligne);
                    j.setRapport(rapport);
                    jobs.save(j);
                });
            }
            fin(jobId, JobRetypage.Statut.TERMINE);
        } catch (RuntimeException e) {
            log.error("Re-typologisation {} interrompue", jobId, e);
            fin(jobId, JobRetypage.Statut.ECHEC);
        }
    }

    private void fin(UUID jobId, JobRetypage.Statut statut) {
        parDocument.executeWithoutResult(s -> {
            JobRetypage j = jobs.findById(jobId).orElseThrow();
            j.setStatut(statut);
            j.setFinLe(Instant.now());
            jobs.save(j);
        });
    }

    /** Un document, dans sa transaction : ligne de rapport, jamais d'exception. */
    private Map<String, Object> traiter(JobRetypage job, UUID documentId) {
        Map<String, Object> ligne = new LinkedHashMap<>();
        ligne.put("documentId", documentId);
        try {
            parDocument.executeWithoutResult(s -> retyper(job, documentId, ligne));
            ligne.put("resultat", "SUCCES");
        } catch (ConflitAutorisationException e) {
            ligne.put("resultat", "ECHEC");
            ligne.put("motif", e.code());
        } catch (MetadonneesInvalidesException e) {
            ligne.put("resultat", "ECHEC");
            ligne.put("motif", MetadonneesInvalidesException.CODE);
            ligne.put("erreurs", e.erreurs());
        } catch (RuntimeException e) {
            ligne.put("resultat", "ECHEC");
            ligne.put("motif", e.getMessage());
        }
        return ligne;
    }

    private void retyper(JobRetypage job, UUID documentId, Map<String, Object> ligne) {
        UploadDocument d = documents.findByIdPourEcriture(documentId)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
        ligne.put("nom", d.getName());
        if (!d.getTypeDocument().getId().equals(job.getSourceTypeDocumentId())) {
            throw new IllegalStateException("Le document n'est plus du type source (déjà traité ?)");
        }
        garde.exigerModifiable(documentId);
        TypeDocument cible = types.findById(job.getCibleTypeDocumentId()).orElseThrow();

        Map<String, String> corr = new HashMap<>();
        job.getCorrespondance().forEach((k, v) -> corr.put(k.toLowerCase(Locale.ROOT), v));
        Map<String, Object> avant = new LinkedHashMap<>(d.getMetadonnees());
        Map<String, Object> transposees = new LinkedHashMap<>();
        List<String> perdus = new ArrayList<>();
        var planCible = cibleDefinition(cible);
        avant.forEach((code, valeur) -> {
            String nouveau = corr.getOrDefault(code.toLowerCase(Locale.ROOT), code);
            if (planCible.champ(nouveau).isPresent()) transposees.put(nouveau, valeur);
            else perdus.add(code);
        });
        if (!perdus.isEmpty()) ligne.put("champsPerdus", perdus);

        String typeAvant = d.getTypeDocument().getCode();
        if (!d.getWorkspace().getId().equals(cible.getWorkspace().getId())) {
            modele.deplacer(d, cible.getWorkspace());
        }
        modele.changerType(d, cible, transposees);
        documents.save(d);

        Map<String, Object> av = new LinkedHashMap<>();
        av.put("type", typeAvant);
        av.put("metadonnees", avant);
        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("type", cible.getCode());
        ap.put("metadonnees", new LinkedHashMap<>(d.getMetadonnees()));
        evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.DOCUMENT_RETYPE, documentId,
                av, ap, "job_retypage " + job.getId(), job.getDemandeurUtilisateurId()));
    }

    private com.ipt.ged.planindexation.metamodele.DefinitionPlan cibleDefinition(TypeDocument cible) {
        return com.ipt.ged.planindexation.metamodele.DefinitionPlan.depuis(cible.getPlanIndexation());
    }
}
