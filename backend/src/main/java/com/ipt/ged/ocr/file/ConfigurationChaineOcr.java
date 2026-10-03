package com.ipt.ged.ocr.file;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.ocr.ExtracteurBureautique;
import com.ipt.ged.ocr.moteur.EchecOcrException;
import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.LanguesOcr;
import com.ipt.ged.ocr.moteur.ModelesEntiers;
import com.ipt.ged.ocr.moteur.MoteurTesseract;
import com.ipt.ged.ocr.moteur.OcrEngine;
import com.ipt.ged.ocr.moteur.ReglageOcr;
import com.ipt.ged.recherche.PredicatDroits;
import com.ipt.ged.recherche.ReindexationComplete;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.recherche.SearchIndexerPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
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

    private static final Logger log = LoggerFactory.getLogger(ConfigurationChaineOcr.class);

    /** Modèles et résolution réellement employés, journalisés au démarrage. */
    @Bean
    public ReglageOcr reglageOcr(@Value("${ged.ocr.commande:tesseract}") String commande,
                                 @Value("${ged.ocr.tessdata:}") String tessdata,
                                 @Value("${ged.ocr.modeles:entiers}") String modeles,
                                 @Value("${ged.ocr.modeles-entiers.repertoire:${java.io.tmpdir}/ged-tessdata-entiers}")
                                 String repertoireEntiers,
                                 @Value("${ged.ocr.modeles-entiers.combine-tessdata:}") String combine,
                                 ProprietesChaineOcr p) {
        ReglageOcr r = reglage(commande, tessdata, modeles, repertoireEntiers, combine, p.getDpi());
        log.info("Réglage OCR : modèles {} ({}), pages PDF rendues à {} dpi", r.modeles(),
                r.tessdata().isEmpty() ? "installation de Tesseract" : r.tessdata(), r.dpi());
        return r;
    }

    @Bean
    public OcrEngine ocrEngine(@Value("${ged.ocr.commande:tesseract}") String commande,
                               @Value("${ged.ocr.oem:1}") String oem,
                               @Value("${ged.ocr.psm:3}") String psm,
                               ReglageOcr reglage) {
        return new MoteurTesseract(commande, reglage.tessdata(), oem, psm);
    }

    /** Moteur Tesseract selon {@link #reglage} (la résolution n'y joue aucun rôle). */
    static MoteurTesseract moteur(String commande, String tessdata, String oem, String psm, String modeles,
                                  String repertoireEntiers, String combine) {
        return new MoteurTesseract(commande, reglage(commande, tessdata, modeles, repertoireEntiers, combine,
                new ProprietesChaineOcr().getDpi()).tessdata(), oem, psm);
    }

    /**
     * Modèles livrés ({@code ged.ocr.modeles=precis}) ou leur copie compactée en
     * entiers ({@code entiers}, défaut : P-14, R30 ; {@link ModelesEntiers}),
     * avec repli sur les modèles livrés si aucun n'a pu être compacté. Une autre
     * valeur empêche le démarrage.
     */
    static ReglageOcr reglage(String commande, String tessdata, String modeles, String repertoireEntiers,
                              String combine, int dpi) {
        // Vide (variable d'environnement exportée sans valeur) : le défaut.
        String choix = modeles == null || modeles.isBlank() ? "entiers"
                : modeles.strip().toLowerCase(java.util.Locale.ROOT);
        if (!choix.equals("entiers") && !choix.equals("precis")) {
            throw new IllegalArgumentException("ged.ocr.modeles : « entiers » ou « precis » attendu, reçu « "
                    + modeles + " »");
        }
        String repertoire = tessdata == null ? "" : tessdata.strip();
        // Répertoire vide = modèles de l'installation de Tesseract : rien à dériver.
        if (repertoire.isEmpty()) return new ReglageOcr("installation", repertoire, dpi);
        if (choix.equals("precis")) return new ReglageOcr("precis", repertoire, dpi);
        String outil = combine == null || combine.isBlank() ? ModelesEntiers.combineParDefaut(commande) : combine;
        Path cible = repertoireEntiers == null || repertoireEntiers.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "ged-tessdata-entiers") : Path.of(repertoireEntiers);
        ModelesEntiers.Preparation p = ModelesEntiers.preparer(Path.of(repertoire).toAbsolutePath(), cible, outil);
        return new ReglageOcr(p.mode(), p.tessdata().toString(), dpi);
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

    /** Enfilage au dépôt (temps 1 du §12.11) ; inerte si la chaîne est désactivée. */
    @Bean
    public EnfilageOcr enfilageOcr(OcrJobQueue file, LanguesOcr langues, ProprietesChaineOcr p) {
        return new EnfilageOcr(file, langues, p.isActif());
    }

    /* PredicatDroits : fourni par le lot autorisation (ConfigurationAutorisation,
       AccessPredicate.predicatSql), seule implémentation depuis la fusion d'E3. */

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
                                                       ProprietesChaineOcr p, ApplicationEventPublisher evenements) {
            // Nom unique entre instances : « pid@hôte » de la JVM, puis le rang.
            String instance = ManagementFactory.getRuntimeMXBean().getName();
            TransactionTemplate tx = new TransactionTemplate(tm);
            return new PoolTravailleursOcr(p.getWorkers(), p.getScrutation(), i -> new TravailleurOcr(
                    instance + "#" + i, file, source, extracteur, indexer, tx, metriques, p.getBail(), evenements));
        }
    }
}
