package com.ipt.ged.autorisation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Compteur {@code version_habilitations} (dossier technique §12.2.3).
 *
 * <p>Il avance à chaque modification de nœud, d'habilitation, de membre ou de
 * corbeille d'un groupe, de composition ou d'indicateur d'un rôle : des
 * déclencheurs de la base s'en chargent (changeset 202609281055), quel que
 * soit le chemin d'écriture. Les droits résolus en cache portent la valeur lue
 * à leur calcul ; dès que la valeur en base diffère, ils sont recalculés. L'effet
 * est donc immédiat, y compris entre plusieurs instances, sans message
 * d'invalidation.
 *
 * <p>La valeur vient d'une séquence : une transaction annulée ne rend pas son
 * numéro, si bien que des droits calculés sur un état jamais validé ne peuvent
 * pas être resservis sous un numéro réutilisé.
 */
@Component
public class VersionHabilitations {

    private final JdbcTemplate jdbc;

    public VersionHabilitations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long lire() {
        Long v = jdbc.queryForObject("SELECT max(valeur) FROM version_habilitations", Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Invalidation explicite, pour une source d'attributions qui ne vit pas
     * dans les tables surveillées (point d'extension {@link SourceHabilitations},
     * par exemple la portée des clés d'API si elle n'a pas son propre
     * déclencheur). Les services du lot l'appellent aussi par clarté.
     */
    public void incrementer() {
        jdbc.update("UPDATE version_habilitations SET valeur = nextval('version_habilitations_seq')");
    }
}
