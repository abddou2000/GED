package com.ipt.ged.cycledevie;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dossiers portés par les espaces de travail actuels (table {@code workspace},
 * arborescence par {@code parent_id}, drapeau d'archivage = statut
 * {@code ARCHIVE} de l'espace), en attendant les nœuds du lot E3.
 *
 * <p>Seule implémentation du cycle de vie qui lit le référentiel des dossiers ;
 * remplacée au lot E3 (dev1) par une implémentation sur les nœuds et les
 * rattachements multiples.
 */
public class DossiersEspaces implements Dossiers {

    private static final String SOUS_ARBRE = """
            WITH RECURSIVE arbre(id, parent_id, name, chemin) AS (
                SELECT id, parent_id, name, CAST('' AS text) FROM workspace WHERE id = ?
                UNION ALL
                SELECT w.id, w.parent_id, w.name,
                       CASE WHEN a.chemin = '' THEN w.name ELSE a.chemin || '/' || w.name END
                  FROM workspace w JOIN arbre a ON w.parent_id = a.id
                 WHERE NOT w.deleted
            )
            """;

    private final JdbcTemplate jdbc;

    public DossiersEspaces(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Dossier> trouver(UUID dossierId) {
        return jdbc.query("SELECT id, name, status FROM workspace WHERE id = ? AND NOT deleted",
                (rs, i) -> new Dossier(rs.getObject("id", UUID.class), rs.getString("name"),
                        "ARCHIVE".equals(rs.getString("status"))), dossierId).stream().findFirst();
    }

    @Override
    public boolean estArchive(UUID dossierId) {
        if (dossierId == null) return false;
        Boolean archive = jdbc.queryForObject("""
                WITH RECURSIVE ascendants(id, parent_id, status) AS (
                    SELECT id, parent_id, status FROM workspace WHERE id = ?
                    UNION ALL
                    SELECT w.id, w.parent_id, w.status FROM workspace w JOIN ascendants a ON w.id = a.parent_id
                )
                SELECT coalesce(bool_or(status = 'ARCHIVE'), false) FROM ascendants
                """, Boolean.class, dossierId);
        return Boolean.TRUE.equals(archive);
    }

    @Override
    public void marquerArchive(UUID dossierId, boolean archive) {
        jdbc.update("UPDATE workspace SET status = ? WHERE id = ?", archive ? "ARCHIVE" : "ACTIF", dossierId);
    }

    @Override
    public List<DocumentRange> documents(UUID dossierId) {
        Map<UUID, List<String>> chemins = new HashMap<>();
        List<UUID> ordre = new ArrayList<>();
        jdbc.query(SOUS_ARBRE + """
                SELECT d.id, a.chemin FROM arbre a JOIN document d ON d.workspace_id = a.id
                 WHERE NOT d.deleted
                 ORDER BY a.chemin, d.created_at, d.id
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            if (!chemins.containsKey(id)) ordre.add(id);
            chemins.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("chemin"));
        }, dossierId);
        return ordre.stream().map(id -> new DocumentRange(id, List.copyOf(chemins.get(id)))).toList();
    }
}
