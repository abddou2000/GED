package com.ipt.ged.cycledevie;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dossiers portés par les nœuds du lot E3 (table {@code noeud}, arborescence
 * par {@code parent_id}) et emplacements des documents : emplacement principal
 * ({@code document.noeud_principal_id}) et rattachements complémentaires
 * ({@code document_rattachement}, §12.4).
 *
 * <p>Un document rangé ou rattaché plusieurs fois sous le dossier exporté n'y
 * figure qu'<b>une fois</b>, avec tous ses chemins dans la sélection : le
 * premier est l'emplacement principal s'il est dans l'arborescence exportée,
 * sinon le premier rattachement (§12.10, « export de dossier »).
 */
public class DossiersNoeuds implements Dossiers {

    private static final String SOUS_ARBRE = """
            WITH RECURSIVE arbre(id, chemin) AS (
                SELECT id, CAST('' AS text) FROM noeud WHERE id = ? AND NOT supprime
                UNION ALL
                SELECT n.id, CASE WHEN a.chemin = '' THEN n.name ELSE a.chemin || '/' || n.name END
                  FROM noeud n JOIN arbre a ON n.parent_id = a.id
                 WHERE NOT n.supprime
            ),
            emplacements(document_id, chemin, rang, cree_le) AS (
                SELECT d.id, a.chemin, 0, d.created_at
                  FROM document d JOIN arbre a ON a.id = d.noeud_principal_id
                 WHERE NOT d.supprime
                UNION ALL
                SELECT d.id, a.chemin, 1, r.cree_le
                  FROM document_rattachement r JOIN document d ON d.id = r.document_id
                  JOIN arbre a ON a.id = r.noeud_id
                 WHERE NOT d.supprime
            )
            """;

    private final JdbcTemplate jdbc;

    public DossiersNoeuds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Dossier> trouver(UUID dossierId) {
        return jdbc.query("SELECT id, name FROM noeud WHERE id = ? AND NOT supprime",
                (rs, i) -> new Dossier(rs.getObject("id", UUID.class), rs.getString("name")), dossierId)
                .stream().findFirst();
    }

    @Override
    public List<DocumentRange> documents(UUID dossierId) {
        Map<UUID, List<String>> chemins = new HashMap<>();
        List<UUID> ordre = new ArrayList<>();
        jdbc.query(SOUS_ARBRE + """
                SELECT document_id, chemin FROM emplacements
                 ORDER BY rang, chemin, cree_le, document_id
                """, rs -> {
            UUID id = rs.getObject("document_id", UUID.class);
            if (!chemins.containsKey(id)) ordre.add(id);
            List<String> l = chemins.computeIfAbsent(id, k -> new ArrayList<>());
            String c = rs.getString("chemin");
            if (!l.contains(c)) l.add(c);
        }, dossierId);
        return ordre.stream().map(id -> new DocumentRange(id, List.copyOf(chemins.get(id)))).toList();
    }
}
