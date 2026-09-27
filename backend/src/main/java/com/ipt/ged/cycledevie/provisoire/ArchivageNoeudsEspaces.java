package com.ipt.ged.cycledevie.provisoire;

import com.ipt.ged.common.StatutConservation;
import com.ipt.ged.workspace.archivage.ArchivageNoeuds;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Implémentation <b>provisoire</b> du contrat {@link ArchivageNoeuds} de dev1
 * (lot modèle E7) sur les espaces de travail de cette branche : drapeau =
 * statut {@code ARCHIVE} de l'espace et de sa sous-arborescence
 * ({@code parent_id}), emplacement principal = {@code document.workspace_id}.
 *
 * <p>Déclarée seulement en l'absence d'une autre implémentation : à la fusion
 * avec le lot de dev1, {@code ArchivageNoeudsJdbc} (nœuds à chemin matérialisé)
 * la remplace, et cette classe est à supprimer.
 */
public class ArchivageNoeudsEspaces implements ArchivageNoeuds {

    private static final String SOUS_ARBRE = """
            WITH RECURSIVE arbre(id) AS (
                SELECT id FROM workspace WHERE id = ?
                UNION ALL
                SELECT w.id FROM workspace w JOIN arbre a ON w.parent_id = a.id
            )
            """;

    private final JdbcTemplate jdbc;

    public ArchivageNoeudsEspaces(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StatutConservation statut(UUID noeudId) {
        List<String> l = jdbc.queryForList("SELECT status FROM workspace WHERE id = ?", String.class, noeudId);
        if (l.isEmpty()) throw new EntityNotFoundException("Dossier introuvable : " + noeudId);
        return "ARCHIVE".equals(l.get(0)) ? StatutConservation.ARCHIVE : StatutConservation.ACTIF;
    }

    @Override
    public int marquerArchive(UUID noeudId, UUID auteurUtilisateurId) {
        return jdbc.update(SOUS_ARBRE + "UPDATE workspace SET status = 'ARCHIVE' "
                + "WHERE id IN (SELECT id FROM arbre) AND status <> 'ARCHIVE'", noeudId);
    }

    @Override
    public int marquerActif(UUID noeudId) {
        return jdbc.update(SOUS_ARBRE + "UPDATE workspace SET status = 'ACTIF' "
                + "WHERE id IN (SELECT id FROM arbre) AND status = 'ARCHIVE'", noeudId);
    }

    @Override
    public List<UUID> documentsAArchiver(UUID noeudId, UUID apres, int taille) {
        String sql = SOUS_ARBRE + "SELECT d.id FROM document d WHERE d.workspace_id IN (SELECT id FROM arbre) "
                + "AND NOT d.deleted AND d.statut_conservation = 'ACTIF'"
                + (apres != null ? " AND d.id > ?" : "") + " ORDER BY d.id LIMIT ?";
        return apres != null
                ? jdbc.queryForList(sql, UUID.class, noeudId, apres, Math.max(1, taille))
                : jdbc.queryForList(sql, UUID.class, noeudId, Math.max(1, taille));
    }
}
