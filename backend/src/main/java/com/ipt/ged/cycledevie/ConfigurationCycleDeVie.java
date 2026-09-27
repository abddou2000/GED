package com.ipt.ged.cycledevie;

import com.ipt.ged.cycledevie.conservation.ConvertisseurPdfA;
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
 * <p>S'appuie sur les lots de dev1 : permissions Purger et Archiver par
 * {@code ControleAcces} (E3), contrats d'archivage {@code ArchivageNoeuds} et
 * {@code ArchivageDocuments} (E7 modèle), garde d'écriture {@code GardeEcriture}.
 */
@Configuration
@EnableConfigurationProperties(ProprietesCycleDeVie.class)
public class ConfigurationCycleDeVie {

    /** Plafond d'une copie de conservation (un PDF rendu en images dépasse l'original). */
    private static final long PLAFOND_COPIE_OCTETS = 4L * 1024 * 1024 * 1024;

    @Bean
    @ConditionalOnMissingBean(Dossiers.class)
    public Dossiers dossiers(JdbcTemplate jdbc) {
        return new DossiersNoeuds(jdbc);
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
