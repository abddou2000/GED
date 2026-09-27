package com.ipt.ged.fichier;

import com.ipt.ged.fichier.cles.DepotClesFichier;
import com.ipt.ged.fichier.cles.DepotClesFichierJdbc;
import com.ipt.ged.fichier.cles.KeyProvider;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.cles.RotationKek;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.AntivirusDesactive;
import com.ipt.ged.fichier.controle.ClientClamd;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.integrite.SourceEmpreintes;
import com.ipt.ged.fichier.integrite.SourceEmpreintesVersions;
import com.ipt.ged.fichier.integrite.VerificationIntegrite;
import com.ipt.ged.fichier.integrite.VerificationPeriodique;
import com.ipt.ged.fichier.previsualisation.ControleAccesPrevisualisation;
import com.ipt.ged.fichier.previsualisation.ConvertisseurBureautique;
import com.ipt.ged.fichier.previsualisation.ConvertisseurLibreOffice;
import com.ipt.ged.fichier.previsualisation.ServicePrevisualisation;
import com.ipt.ged.fichier.stockage.FileStore;
import com.ipt.ged.fichier.stockage.FileStoreDisque;
import com.ipt.ged.supervision.VerificationAntivirus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Assemblage du stockage sécurisé (lot E5).
 *
 * <p>Les composants sont des classes simples, assemblées ici plutôt
 * qu'annotées : chacun se teste sans contexte Spring, et le jour où un KMS
 * remplace le keystore ou S3 remplace le disque, seule cette classe change.
 */
@Configuration
@EnableConfigurationProperties(ProprietesFichiers.class)
public class ConfigurationFichiers {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationFichiers.class);
    private static final long MO = 1024L * 1024L;

    @Bean
    public FileStore fileStore(ProprietesFichiers p) {
        return new FileStoreDisque(Path.of(p.getRacine()));
    }

    @Bean
    public KeyProvider keyProvider(ProprietesFichiers p) {
        ProprietesFichiers.Cles c = p.getCles();
        if (c.getKeystore() == null || c.getKeystore().isBlank()) {
            throw new IllegalStateException("Chemin du keystore des clés maîtresses absent : renseigner GED_KEYSTORE_CHEMIN.");
        }
        char[] mdp = c.getMotDePasse() != null ? c.getMotDePasse().toCharArray() : new char[0];
        Path keystore = Path.of(c.getKeystore()).toAbsolutePath().normalize();
        // Une clé rangée avec les fichiers qu'elle chiffre ne les protège pas :
        // une copie du volume emporterait les deux.
        if (keystore.startsWith(Path.of(p.getRacine()).toAbsolutePath().normalize())) {
            throw new IllegalStateException("Le keystore ne doit pas se trouver sous la racine du stockage de fichiers.");
        }
        return new KeystoreKeyProvider(keystore, mdp, c.isCreerSiAbsent());
    }

    @Bean
    public DepotClesFichier depotClesFichier(JdbcTemplate jdbc) {
        return new DepotClesFichierJdbc(jdbc);
    }

    @Bean
    public StockageChiffre stockageChiffre(FileStore fileStore, KeyProvider keyProvider, DepotClesFichier cles,
                                           ProprietesFichiers p) {
        return new StockageChiffre(fileStore, keyProvider, cles, p.getTailleSegment());
    }

    @Bean
    public RotationKek rotationKek(KeyProvider keyProvider, DepotClesFichier cles) {
        return new RotationKek(keyProvider, cles);
    }

    @Bean
    public VerificationIntegrite verificationIntegrite(StockageChiffre stockage, ApplicationEventPublisher evenements) {
        return new VerificationIntegrite(stockage, evenements);
    }

    /** Empreintes à vérifier : toutes les versions chiffrées (vérification mensuelle, §6.1.4). */
    @Bean
    public SourceEmpreintes sourceEmpreintes(JdbcTemplate jdbc) {
        return new SourceEmpreintesVersions(jdbc);
    }

    @Bean
    public DetecteurTypeReel detecteurTypeReel() {
        return new DetecteurTypeReel();
    }

    @Bean
    public AnalyseurAntivirus analyseurAntivirus(ProprietesFichiers p, Environment env) {
        ProprietesFichiers.Antivirus a = p.getAntivirus();
        if (!a.isActif()) {
            // Liste blanche plutôt que liste noire : seuls les postes de
            // développement et les tests peuvent s'en passer. Le profil uat
            // (qui importe la configuration de prod sans activer le profil
            // prod) et tout profil inconnu refusent de démarrer.
            if (!env.acceptsProfiles(Profiles.of("dev | test"))
                    || env.acceptsProfiles(Profiles.of("prod | uat"))) {
                throw new IllegalStateException("L'antivirus ne peut être désactivé qu'en développement ou en test "
                        + "(profils actifs : " + String.join(",", env.getActiveProfiles()) + ", §6.1.5).");
            }
            log.warn("ANTIVIRUS DESACTIVE (ged.fichiers.antivirus.actif=false) : poste de développement uniquement.");
            return new AntivirusDesactive();
        }
        return new ClientClamd(a.getHote(), a.getPort(), a.getDelaiConnexionMs(), a.getDelaiLectureMs(),
                a.getTailleBlocOctets());
    }

    /**
     * Sonde de santé « antivirus » du lot exploitation : elle teste exactement
     * le client du dépôt (zPING sur le même hôte, port et délai).
     */
    @Bean
    public VerificationAntivirus verificationAntivirus(AnalyseurAntivirus analyseur) {
        return analyseur::disponible;
    }

    @Bean
    public ControleFichiers controleFichiers(DetecteurTypeReel detecteur, AnalyseurAntivirus antivirus,
                                             StockageChiffre stockage, ApplicationEventPublisher evenements,
                                             ProprietesFichiers p) {
        return new ControleFichiers(detecteur, antivirus, stockage, evenements,
                p.getTailleMaxDefautMo(), p.getPlafondPlateformeMo(), p.getFormatsParDefaut());
    }

    @Bean
    public ConvertisseurBureautique convertisseurBureautique(ProprietesFichiers p) {
        return new ConvertisseurLibreOffice(p.getPrevisualisation().getLibreofficeCommande(),
                Duration.ofSeconds(p.getPrevisualisation().getDelaiConversionS()));
    }

    @Bean
    public ServicePrevisualisation servicePrevisualisation(StockageChiffre stockage, KeyProvider keyProvider,
                                                           DepotClesFichier cles, ConvertisseurBureautique convertisseur,
                                                           ProprietesFichiers p) {
        // Le cache a son propre référentiel mais partage les clés : chaque
        // aperçu a sa DEK dans cle_fichier, comme un document.
        StockageChiffre cache = new StockageChiffre(new FileStoreDisque(Path.of(p.getRacineCacheApercu())),
                keyProvider, cles, p.getTailleSegment());
        return new ServicePrevisualisation(stockage, cache, convertisseur,
                Path.of(p.getPrevisualisation().getRepertoireTravail()), p.getPlafondPlateformeMo() * MO);
    }

    /* ControleAccesPrevisualisation : fourni par le lot autorisation
       (ConfigurationAutorisation), même décision que le téléchargement. */

    /** Vérification mensuelle d'intégrité de toutes les versions chiffrées. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "ged.fichiers.integrite", name = "verification-planifiee", havingValue = "true")
    static class VerificationPlanifiee {
        @Bean
        public VerificationPeriodique verificationPeriodique(VerificationIntegrite verification, SourceEmpreintes source) {
            return new VerificationPeriodique(verification, source);
        }
    }

    /** Rotation annuelle de la KEK (§6.1.2), désactivée par défaut : la sauvegarde du keystore doit suivre. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "ged.fichiers.cles", name = "rotation-planifiee", havingValue = "true")
    static class RotationPlanifiee {
        private final RotationKek rotation;

        RotationPlanifiee(RotationKek rotation) {
            this.rotation = rotation;
        }

        @Scheduled(cron = "${ged.fichiers.cles.rotation-cron:0 0 2 1 1 *}")
        public void executer() {
            rotation.executer();
        }
    }
}
