package com.ipt.ged.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implémentation <b>transitoire</b> de {@link AnnuaireDestinataires}, sur le
 * modèle actuel de cette branche : l'identité est l'employé, son adresse est
 * celle de son compte de connexion, les groupes sont les groupes d'accès.
 *
 * <p>À remplacer à la fusion par l'implémentation du lot identité (dev1, E2 :
 * courriel lu dans {@code cache_annuaire}) et du lot autorisation (E3 : rôles).
 * Tant qu'aucun rôle n'existe, {@link #porteursDuRole} ne trouve personne et le
 * signale au journal technique : une alerte d'échéance reste alors sans
 * destinataire, ce qui se voit, plutôt que d'être envoyée au hasard.
 */
public class AnnuaireDestinatairesLocal implements AnnuaireDestinataires {

    private static final Logger journal = LoggerFactory.getLogger(AnnuaireDestinatairesLocal.class);

    private final JdbcTemplate jdbc;

    public AnnuaireDestinatairesLocal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> courriel(UUID utilisateurId) {
        return jdbc.queryForList("SELECT email FROM compte_utilisateur WHERE employe_id = ? AND actif",
                        String.class, utilisateurId).stream()
                .filter(e -> e != null && !e.isBlank())
                .findFirst();
    }

    @Override
    public Set<UUID> membresDuGroupe(UUID groupeId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT employe_id FROM access_group_employe WHERE access_group_id = ?", UUID.class, groupeId));
    }

    @Override
    public Map<UUID, String> espacesDuGroupe(UUID groupeId) {
        Map<UUID, String> espaces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT w.id, w.name FROM access_group_workspace agw
                JOIN workspace w ON w.id = agw.workspace_id
                WHERE agw.access_group_id = ? ORDER BY w.name""",
                rs -> { espaces.put(rs.getObject(1, UUID.class), rs.getString(2)); }, groupeId);
        return espaces;
    }

    @Override
    public Set<UUID> porteursDuRole(String codeRole) {
        journal.warn("Rôle {} non résolu : aucun modèle de rôles dans cette version (lot autorisation E3)", codeRole);
        return Set.of();
    }
}
