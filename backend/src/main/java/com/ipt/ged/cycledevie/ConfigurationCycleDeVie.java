package com.ipt.ged.cycledevie;

import com.ipt.ged.cycledevie.conservation.ConvertisseurPdfA;
import com.ipt.ged.cycledevie.provisoire.ArchivageDocumentsProvisoire;
import com.ipt.ged.cycledevie.provisoire.ArchivageNoeudsEspaces;
import com.ipt.ged.document.archivage.ArchivageDocuments;
import com.ipt.ged.workspace.archivage.ArchivageNoeuds;
import com.ipt.ged.cycledevie.conservation.CopiesConservation;
import com.ipt.ged.cycledevie.conservation.ValidateurPdfA;
import com.ipt.ged.cycledevie.conservation.ValidateurVeraPdf;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.previsualisation.ConvertisseurBureautique;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

/**
 * Cycle de vie des documents (lot E7, dev3) : purge, archivage et copie de
 * conservation, export de dossier.
 *
 * <p>Points d'extension fournis par d'autres lots, remplaçables par un bean du
 * même type : {@link AutorisationsCycleDeVie} (permissions Purger et Archiver,
 * E3), {@link Dossiers} (nœuds et rattachements, E3), et les contrats
 * d'archivage du lot modèle {@link ArchivageNoeuds} et {@link ArchivageDocuments}
 * (dev1), dont les implémentations provisoires ne servent qu'avant la fusion.
 */
@Configuration
@EnableConfigurationProperties(ProprietesCycleDeVie.class)
public class ConfigurationCycleDeVie {

    /** Plafond d'une copie de conservation (un PDF rendu en images dépasse l'original). */
    private static final long PLAFOND_COPIE_OCTETS = 4L * 1024 * 1024 * 1024;

    @Bean
    @ConditionalOnMissingBean(AutorisationsCycleDeVie.class)
    public AutorisationsCycleDeVie autorisationsCycleDeVie() {
        return new AutorisationsCycleDeVieProvisoires();
    }

    @Bean
    @ConditionalOnMissingBean(Dossiers.class)
    public Dossiers dossiers(JdbcTemplate jdbc) {
        return new DossiersEspaces(jdbc);
    }

    /** Contrat du lot modèle (dev1) ; implémentation provisoire sur les espaces, à retirer à la fusion. */
    @Bean
    @ConditionalOnMissingBean(ArchivageNoeuds.class)
    public ArchivageNoeuds archivageNoeuds(JdbcTemplate jdbc) {
        return new ArchivageNoeudsEspaces(jdbc);
    }

    /** Contrat du lot modèle (dev1) ; implémentation provisoire, à retirer à la fusion. */
    @Bean
    @ConditionalOnMissingBean(ArchivageDocuments.class)
    public ArchivageDocuments archivageDocuments(JdbcTemplate jdbc) {
        return new ArchivageDocumentsProvisoire(jdbc);
    }

    @Bean
    public ValidateurPdfA validateurPdfA() {
        return new ValidateurVeraPdf();
    }

    @Bean
    public ConvertisseurPdfA convertisseurPdfA(ConvertisseurBureautique libreOffice, ValidateurPdfA validateur) {
        return new ConvertisseurPdfA(libreOffice, validateur);
    }

    @Bean
    public CopiesConservation copiesConservation(JdbcTemplate jdbc, StockageChiffre stockage,
                                                 ConvertisseurPdfA convertisseur, ProprietesCycleDeVie p) {
        return new CopiesConservation(jdbc, stockage, convertisseur,
                Path.of(p.getArchivage().getRepertoireTravail()), PLAFOND_COPIE_OCTETS);
    }
}
