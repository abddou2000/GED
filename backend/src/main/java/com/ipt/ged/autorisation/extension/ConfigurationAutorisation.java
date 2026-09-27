package com.ipt.ged.autorisation.extension;

import com.ipt.ged.audit.GardeConsultationAudit;
import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.PermissionRefuseeException;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.previsualisation.ControleAccesPrevisualisation;
import com.ipt.ged.recherche.PredicatDroits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Branchement du point d'application unique sur les points d'extension des
 * autres lots (dossier technique §12.2.3) : ils ne réimplémentent pas la règle,
 * ils reçoivent l'implémentation du lot autorisation.
 *
 * <ul>
 *   <li>{@link PredicatDroits} (dev3, paquet {@code recherche}) : prédicat SQL
 *       « à la source » de la recherche plein texte, des extraits et des totaux ;</li>
 *   <li>{@link ControleAccesPrevisualisation} (dev3, paquet {@code fichier}) :
 *       l'aperçu applique la même décision que le téléchargement ;</li>
 *   <li>{@link GardeConsultationAudit} (dev2, paquet {@code audit}) : le journal
 *       n'est consultable qu'avec la permission {@code CONSULTER_AUDIT}
 *       (Administrateur et Direction Générale).</li>
 * </ul>
 * Les beans sont {@link Primary} : les implémentations provisoires déclarées
 * par les lots propriétaires restent en place et cèdent le pas sans qu'aucun
 * de leurs fichiers ne soit modifié.
 */
@Configuration
public class ConfigurationAutorisation {

    /**
     * Filtre de la recherche : documents vivants que l'appelant peut consulter
     * (emplacement accessible ou document isolé, et confidentialité).
     */
    @Bean
    @Primary
    public PredicatDroits predicatDroitsAutorisation(AccessPredicate droits) {
        return (colonneDocumentId, utilisateur) ->
                droits.predicatSql(colonneDocumentId, utilisateur, CodePermission.CONSULTER);
    }

    /**
     * Aperçu d'une version : même décision que le téléchargement. Hors périmètre,
     * la réponse est celle d'une version inexistante (P5).
     */
    @Bean
    @Primary
    public ControleAccesPrevisualisation controleAccesPrevisualisationAutorisation(AccessPredicate droits) {
        return (version, utilisateur) -> {
            UUID documentId;
            try {
                documentId = UUID.fromString(version.documentId());
            } catch (RuntimeException e) {
                throw Refus.introuvable("version " + version.versionId());
            }
            if (!droits.peut(utilisateur, CodePermission.CONSULTER, documentId)) {
                throw Refus.introuvable("version " + version.versionId());
            }
        };
    }

    /** Consultation et export du journal d'audit (§7.4.3). */
    @Bean
    @Primary
    public GardeConsultationAudit gardeConsultationAuditAutorisation(AccessPredicate droits) {
        return () -> {
            if (!droits.droits(SecurityContextHolder.getContext().getAuthentication())
                    .administre(CodePermission.CONSULTER_AUDIT)) {
                throw new PermissionRefuseeException(
                        "Consultation du journal d'audit réservée à l'Administrateur et à la Direction Générale.");
            }
        };
    }
}
