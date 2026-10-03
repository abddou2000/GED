package com.ipt.ged.accessgroup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appartenances aux groupes GED en attente d'une identité (T-025, écart 2 ;
 * table {@code groupe_membre_attente}).
 *
 * <p>Au §12.1 le membre d'un groupe est une identité GED ({@code utilisateur}).
 * Or une personne n'a d'identité qu'à sa première connexion : les appartenances
 * reprises de l'ancienne application, et celles que l'Administrateur prépare
 * avant l'ouverture, désignent donc une fiche employé et attendent ici. Elles
 * n'apportent aucun droit tant qu'elles attendent.
 *
 * <p>À la création de l'identité ({@code ServiceIdentites}), {@link #convertir}
 * en fait des appartenances réelles, dans la même transaction et sans action de
 * l'Administrateur : la ligne garde son identifiant, et le déclencheur de
 * {@code groupe_membre} fait avancer {@code version_habilitations}.
 *
 * <p>Requêtes sur le chemin de recherche courant, sans schéma explicite :
 * la reprise les rejoue telles quelles sur un schéma cible.
 */
@Component
public class AppartenancesEnAttente {

    private final JdbcTemplate jdbc;

    public AppartenancesEnAttente(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Convertit les appartenances en attente de la fiche {@code employeId} en
     * appartenances de l'identité {@code utilisateurId}, qui doit déjà exister
     * en base (insertion validée ou écrite par flush).
     *
     * @return nombre de groupes rejoints
     */
    public int convertir(UUID utilisateurId, UUID employeId) {
        int rejoints = jdbc.update("""
                INSERT INTO groupe_membre (id, groupe_ged_id, utilisateur_id)
                SELECT a.id, a.groupe_ged_id, ? FROM groupe_membre_attente a
                 WHERE a.employe_id = ?
                ON CONFLICT DO NOTHING""", utilisateurId, employeId);
        jdbc.update("DELETE FROM groupe_membre_attente WHERE employe_id = ?", employeId);
        return rejoints;
    }
}
