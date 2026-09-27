package com.ipt.ged.ocr.file;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.ocr.ExtracteurBureautique;
import com.ipt.ged.ocr.moteur.EchecOcrException;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.LanguesOcr;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.OcrEngine;
import com.ipt.ged.recherche.PredicatDroits;
import com.ipt.ged.recherche.PredicatDroitsProvisoire;
import com.ipt.ged.recherche.ReindexationComplete;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.recherche.SearchIndexerPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.concurrent.Executors;

/**
 * Assemblage de la chaîne OCR asynchrone et de la recherche plein texte (lot E6).
 *
 * <p>Les services (file, index, moteur) existent toujours : ils ne touchent la
 * base qu'à l'appel. Les workers, les jauges et les points d'entrée ne
 * démarrent qu'avec {@code ged.ocr.chaine.actif=true}, une fois les tables
 * intégrées au changelog.
 */
@Configuration
@EnableConfigurationProperties(ProprietesChaineOcr.class)
public class ConfigurationChaineOcr {

    @Bean
    public OcrEngine ocrEngine(@Value("${ged.ocr.commande:tesseract}") String commande,
                               @Value("${ged.ocr.tessdata:}") String tessdata,
                               @Value("${ged.ocr.oem:1}") String oem,
                               @Value("${ged.ocr.psm:3}") String psm) {
        return new MoteurTesseract(commande, tessdata, oem, psm);
    }

    @Bean
    public LanguesOcr languesOcr(ProprietesChaineOcr p) {
        return new LanguesOcr(p.getLangueDefaut(), p.getLanguesParType());
    }

    @Bean
    public ExtracteurDocumentOcr extracteurDocumentOcr(OcrEngine moteur, ExtracteurBureautique bureautique,
                                                       ProprietesChaineOcr p) {
        return new ExtracteurDocumentOcr(moteur, bureautique, p.getDpi(), p.getSeuilCaracteresParPage(),
                p.getDelaiParPage());
    }

    @Bean
    public OcrJobQueue ocrJobQueue(JdbcTemplate jdbc, ProprietesChaineOcr p) {
        return new OcrJobQueuePostgres(jdbc, new PolitiqueReprise(p.getDelaisReprise()));
    }

    /** Point d'extension du lot autorisation : à remplacer par le prédicat du point unique de droits. */
    @Bean
    public PredicatDroits predicatDroits() {
        return new PredicatDroitsProvisoire();
    }

    @Bean
    public SearchIndexer searchIndexer(JdbcTemplate jdbc, PredicatDroits droits) {
        return new SearchIndexerPostgres(jdbc, droits);
    }

    @Bean
    public ReindexationComplete reindexationComplete(SearchIndexer indexer, ProprietesChaineOcr p) {
        return new ReindexationComplete(indexer, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reindexation-complete");
            t.setDaemon(true);
            return t;
        }), p.getReindexationLot(), p.getReindexationPause());
    }

    /** Lecture déchiffrée en mémoire du fichier d'un job (lot E5). */
    @Bean
    public SourceFichierOcr sourceFichierOcr(StockageChiffre stockage) {
        return id -> {
            try {
                return stockage.lire(id);
            } catch (ErreurFichierException e) {
                throw new EchecOcrException(EchecOcrException.Motif.FICHIER_INTROUVABLE, e.getMessage(), e);
            }
        };
    }

    /** Workers et métriques, seulement quand les tables existent. */
    @Configuration
    @ConditionalOnProperty(prefix = "ged.ocr.chaine", name = "actif", havingValue = "true")
    static class Activee {

        /** File OCR supervisée (sonde filesTraitement, métriques de file du lot exploitation). */
        @Bean
        public FileOcrSupervisee fileOcrSupervisee(OcrJobQueue file) {
            return new FileOcrSupervisee(file, Clock.systemUTC());
        }

        @Bean
        public MetriquesOcr metriquesOcr(MeterRegistry registre, ProprietesChaineOcr p) {
            return new MetriquesOcr(registre, p.getObjectifDisponibilite(), Clock.systemUTC());
        }

        @Bean
        public PoolTravailleursOcr poolTravailleursOcr(OcrJobQueue file, SourceFichierOcr source,
                                                       ExtracteurDocumentOcr extracteur, SearchIndexer indexer,
                                                       PlatformTransactionManager tm, MetriquesOcr metriques,
                                                       ProprietesChaineOcr p) {
            // Nom unique entre instances : « pid@hôte » de la JVM, puis le rang.
            String instance = ManagementFactory.getRuntimeMXBean().getName();
            TransactionTemplate tx = new TransactionTemplate(tm);
            return new PoolTravailleursOcr(p.getWorkers(), p.getScrutation(), i -> new TravailleurOcr(
                    instance + "#" + i, file, source, extracteur, indexer, tx, metriques, p.getBail()));
        }
    }
}
