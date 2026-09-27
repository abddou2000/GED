package com.ipt.ged.notification;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AnnuaireDestinataires} sur le modèle des lots identité (E2) et
 * autorisation (E3) :
 * <ul>
 *   <li>courriel : {@code cache_annuaire.courriel}, lu dans l'annuaire à la
 *       connexion (attribut {@code mail}, décision D3) — jamais une requête
 *       LDAP par notification ;</li>
 *   <li>membres d'un groupe GED : {@code groupe_membre} (employés), traduits en
 *       identités GED par {@code utilisateur.employe_id} ; un membre qui ne
 *       s'est jamais connecté n'a pas d'identité et n'est pas notifié ;</li>
 *   <li>espaces d'un groupe : nœuds vivants sur lesquels le groupe porte une
 *       habilitation avec rôle ;</li>
 *   <li>porteurs d'un rôle : habilitations de portée globale, directes ou par
 *       un groupe vivant (ex. {@code AGENT_ARCHIVE}).</li>
 * </ul>
 */
public class AnnuaireDestinatairesIdentite implements AnnuaireDestinataires {

    private final JdbcTemplate jdbc;

    public AnnuaireDestinatairesIdentite(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> courriel(UUID utilisateurId) {
        return jdbc.queryForList("SELECT courriel FROM cache_annuaire WHERE utilisateur_id = ?",
                        String.class, utilisateurId).stream()
                .filter(e -> e != null && !e.isBlank())
                .findFirst();
    }

    @Override
    public Set<UUID> membresDuGroupe(UUID groupeId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT u.id FROM groupe_membre gm
                JOIN groupe_ged g ON g.id = gm.groupe_ged_id AND NOT g.supprime
                JOIN utilisateur u ON u.employe_id = gm.employe_id
                WHERE gm.groupe_ged_id = ?""", UUID.class, groupeId));
    }

    @Override
    public Set<UUID> identitesDesEmployes(Collection<UUID> employeIds) {
        if (employeIds.isEmpty()) return Set.of();
        return new LinkedHashSet<>(jdbc.queryForList("SELECT id FROM utilisateur WHERE employe_id = ANY (?)",
                UUID.class, (Object) employeIds.toArray(UUID[]::new)));
    }

    @Override
    public Map<UUID, String> espacesDuGroupe(UUID groupeId) {
        Map<UUID, String> espaces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT n.id, n.name FROM habilitation h
                JOIN noeud n ON n.id = h.noeud_id AND NOT n.supprime
                WHERE h.groupe_ged_id = ? AND h.role_id IS NOT NULL
                ORDER BY n.name""",
                rs -> { espaces.put(rs.getObject(1, UUID.class), rs.getString(2)); }, groupeId);
        return espaces;
    }

    @Override
    public Set<UUID> porteursDuRole(String codeRole) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT h.utilisateur_id FROM habilitation h JOIN role r ON r.id = h.role_id
                WHERE r.code = ? AND h.utilisateur_id IS NOT NULL
                  AND h.noeud_id IS NULL AND h.document_id IS NULL
                UNION
                SELECT u.id FROM habilitation h JOIN role r ON r.id = h.role_id
                JOIN groupe_ged g ON g.id = h.groupe_ged_id AND NOT g.supprime
                JOIN groupe_membre gm ON gm.groupe_ged_id = g.id
                JOIN utilisateur u ON u.employe_id = gm.employe_id
                WHERE r.code = ? AND h.noeud_id IS NULL AND h.document_id IS NULL""",
                UUID.class, codeRole, codeRole));
    }
}
