package com.ipt.ged.notification;

import org.springframework.jdbc.core.JdbcTemplate;

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
 *   <li>membres d'un groupe GED : {@code groupe_membre}, qui désigne des
 *       identités GED ; une appartenance en attente (personne qui ne s'est
 *       jamais connectée) n'est pas notifiée ;</li>
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
                SELECT gm.utilisateur_id FROM groupe_membre gm
                JOIN groupe_ged g ON g.id = gm.groupe_ged_id AND NOT g.supprime
                WHERE gm.groupe_ged_id = ?""", UUID.class, groupeId));
    }

    @Override
    public Map<UUID, String> espacesDuGroupe(UUID groupeId) {
        Map<UUID, String> espaces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT n.id, n.nom FROM habilitation h
                JOIN noeud n ON n.id = h.noeud_id AND NOT n.supprime
                WHERE h.groupe_ged_id = ? AND h.role_id IS NOT NULL
                ORDER BY n.nom""",
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
                SELECT gm.utilisateur_id FROM habilitation h JOIN role r ON r.id = h.role_id
                JOIN groupe_ged g ON g.id = h.groupe_ged_id AND NOT g.supprime
                JOIN groupe_membre gm ON gm.groupe_ged_id = g.id
                WHERE r.code = ? AND h.noeud_id IS NULL AND h.document_id IS NULL""",
                UUID.class, codeRole, codeRole));
    }
}
