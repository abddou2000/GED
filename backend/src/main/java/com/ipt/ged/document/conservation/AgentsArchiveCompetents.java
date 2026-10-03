package com.ipt.ged.document.conservation;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.DroitsResolus;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.identite.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Destinataires de l'alerte d'échéance d'un document (§12.9, dossier
 * fonctionnel §4.6.3) : les porteurs du rôle Agent d'archive — attribué
 * directement ou par un groupe, en portée globale ou sur un nœud — qui
 * peuvent <b>archiver ce document</b>. La décision est celle du point
 * d'application unique ({@link AccessPredicate}) : périmètre de leur rôle et
 * confidentialité. Un agent qui ne voit pas un document confidentiel n'en
 * apprend pas l'existence par une notification.
 *
 * <p>Une instance par exécution de la tâche : les droits de chaque agent sont
 * résolus une fois pour tous les documents du passage.
 */
@Component
public class AgentsArchiveCompetents {

    private final JdbcTemplate jdbc;
    private final AccessPredicate droits;

    public AgentsArchiveCompetents(JdbcTemplate jdbc, AccessPredicate droits) {
        this.jdbc = jdbc;
        this.droits = droits;
    }

    /** Résolveur pour un passage de la tâche. */
    public Passage passage() {
        List<Sujet> agents = jdbc.query("""
                SELECT u.id, u.employe_id FROM habilitation h
                  JOIN role r ON r.id = h.role_id
                  JOIN utilisateur u ON u.id = h.utilisateur_id
                 WHERE r.code = ?
                UNION
                SELECT u.id, u.employe_id FROM habilitation h
                  JOIN role r ON r.id = h.role_id
                  JOIN groupe_ged g ON g.id = h.groupe_ged_id AND NOT g.supprime
                  JOIN groupe_membre gm ON gm.groupe_ged_id = g.id
                  JOIN utilisateur u ON u.id = gm.utilisateur_id
                 WHERE r.code = ?""",
                (rs, i) -> new Sujet(TypeSujet.UTILISATEUR, rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), null),
                Role.AGENT_ARCHIVE, Role.AGENT_ARCHIVE);
        return new Passage(agents);
    }

    /** Agents d'archive d'un passage, droits résolus à la demande. */
    public final class Passage {

        private final List<Sujet> agents;
        private final Map<UUID, DroitsResolus> resolus = new LinkedHashMap<>();

        private Passage(List<Sujet> agents) {
            this.agents = agents;
        }

        /** Agents qui détiennent Archiver sur ce document (rôle, périmètre et confidentialité). */
        public List<UUID> pour(UUID documentId) {
            List<UUID> l = new ArrayList<>();
            for (Sujet agent : agents) {
                DroitsResolus d = resolus.computeIfAbsent(agent.id(), id -> droits.droits(agent));
                // Archiver, et non Consulter : un agent d'un autre périmètre qui
                // voit le document par un rôle de lecture n'en est pas chargé.
                if (droits.permissionsSurDocument(d, documentId).contains(CodePermission.ARCHIVER)) l.add(agent.id());
            }
            return l;
        }
    }
}
