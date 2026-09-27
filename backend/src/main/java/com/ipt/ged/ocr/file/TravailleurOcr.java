package com.ipt.ged.ocr.file;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.ocr.moteur.EchecOcrException;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.TexteDocument;
import com.ipt.ged.recherche.SearchIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.ContenuIndexe;
import com.ipt.ged.document.evenement.OcrEnEchec;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Worker OCR (§4.3.4) : réserve un job, extrait le texte page par page,
 * enregistre le texte et met l'index à jour <b>dans la même transaction</b>
 * que la clôture du job — le document devient interrogeable d'un coup, ou pas
 * du tout.
 *
 * <p>Le travail long (OCR) se fait hors transaction, sous bail prolongé à chaque
 * page ; si le bail a été perdu (worker jugé mort et job repris ailleurs), le
 * résultat est abandonné au lieu d'écraser celui de l'autre worker.
 */
public class TravailleurOcr {

    private static final Logger log = LoggerFactory.getLogger(TravailleurOcr.class);

    private final String nom;
    private final OcrJobQueue file;
    private final SourceFichierOcr source;
    private final ExtracteurDocumentOcr extracteur;
    private final SearchIndexer indexer;
    private final TransactionTemplate transaction;
    private final MetriquesOcr metriques;
    private final Duration bail;
    private final ApplicationEventPublisher evenements;

    public TravailleurOcr(String nom, OcrJobQueue file, SourceFichierOcr source, ExtracteurDocumentOcr extracteur,
                          SearchIndexer indexer, TransactionTemplate transaction, MetriquesOcr metriques,
                          Duration bail) {
        this(nom, file, source, extracteur, indexer, transaction, metriques, bail, e -> { });
    }

    /** @param evenements reçoit {@link ContenuIndexe} (dans la transaction de clôture) et {@link OcrEnEchec}. */
    public TravailleurOcr(String nom, OcrJobQueue file, SourceFichierOcr source, ExtracteurDocumentOcr extracteur,
                          SearchIndexer indexer, TransactionTemplate transaction, MetriquesOcr metriques,
                          Duration bail, ApplicationEventPublisher evenements) {
        this.evenements = evenements;
        this.nom = nom;
        this.file = file;
        this.source = source;
        this.extracteur = extracteur;
        this.indexer = indexer;
        this.transaction = transaction;
        this.metriques = metriques;
        this.bail = bail;
    }

    public String nom() {
        return nom;
    }

    /** Traite au plus un job. @return {@code false} si la file ne proposait rien. */
    public boolean traiterUn() {
        List<OcrJob> jobs = file.reserver(nom, 1, bail);
        if (jobs.isEmpty()) return false;
        traiter(jobs.get(0));
        return true;
    }

    private void traiter(OcrJob job) {
        TexteDocument texte;
        try (InputStream contenu = source.ouvrir(job.fichierId())) {
            texte = extracteur.extraire(contenu, job.typeMime(), job.langue(), (page, total) -> {
                if (!file.prolonger(job.id(), nom, bail)) {
                    throw new BailPerdu();
                }
            });
        } catch (BailPerdu e) {
            log.warn("Job OCR {} repris par un autre worker : résultat abandonné", job.id());
            return;
        } catch (EchecOcrException e) {
            echouer(job, e.libelle(), e.definitif());
            return;
        } catch (ErreurFichierException e) {
            // Déchiffrement refusé (GCM) ou fichier purgé : rejouer ne changera rien.
            echouer(job, EchecOcrException.Motif.FICHIER_CORROMPU + " : " + e.getMessage(), true);
            return;
        } catch (IOException | RuntimeException e) {
            log.error("Job OCR {} : erreur inattendue", job.id(), e);
            echouer(job, EchecOcrException.Motif.ERREUR_MOTEUR + " : " + e.getMessage(), false);
            return;
        }

        boolean clos = Boolean.TRUE.equals(transaction.execute(statut -> {
            indexer.indexer(new SearchIndexer.TexteAIndexer(job.documentId(), job.versionId(), job.langue(),
                    texte.texte(), texte.provenance().name(), texte.nbPages()));
            if (!file.terminer(job.id(), nom, texte.nbPages())) {
                statut.setRollbackOnly();
                return false;
            }
            evenements.publishEvent(new ContenuIndexe(job.documentId(), job.versionId(), Acteur.SYSTEME,
                    Instant.now(), texte.nbPages(), texte.provenance().name(),
                    Duration.between(job.deposeLe(), Instant.now())));
            return true;
        }));
        if (clos) {
            Duration d = metriques.disponible(job.deposeLe());
            log.info("Job OCR {} terminé : {} page(s) ({} natives, {} OCR), interrogeable {} s après dépôt",
                    job.id(), texte.nbPages(), texte.pagesNatives(), texte.pagesOcr(), d.toSeconds());
        } else {
            log.warn("Job OCR {} : bail perdu avant la clôture, texte non enregistré", job.id());
        }
    }

    private void echouer(OcrJob job, String motif, boolean definitif) {
        StatutOcr s = file.echouer(job.id(), nom, motif, definitif);
        if (s == StatutOcr.OCR_ECHEC) {
            metriques.echec();
            evenements.publishEvent(new OcrEnEchec(job.documentId(), job.versionId(), Acteur.SYSTEME, Instant.now(),
                    job.id(), motif));
            log.error("Job OCR {} en échec (document « non interrogeable ») : {}", job.id(), motif);
        } else {
            metriques.reprise();
            log.warn("Job OCR {} : tentative {} échouée, reprise programmée ({})", job.id(), job.tentatives(), motif);
        }
    }

    /** Levée depuis le suivi de page quand le bail n'a pas pu être prolongé. */
    private static final class BailPerdu extends RuntimeException {
        BailPerdu() {
            super(null, null, false, false);
        }
    }
}
