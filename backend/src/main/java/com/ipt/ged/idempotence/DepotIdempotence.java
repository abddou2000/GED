package com.ipt.ged.idempotence;

import com.ipt.ged.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès à {@code idempotence_cle}. Chaque instruction est validée aussitôt
 * (aucune transaction englobante) : la réservation d'une clé doit être
 * visible des requêtes concurrentes avant que l'opération ne s'exécute.
 */
@Repository
public class DepotIdempotence {

    private final JdbcTemplate jdbc;

    public DepotIdempotence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Entrée mémorisée. */
    public record Entree(UUID id, String empreinteRequete, String etat, Integer statut, String typeContenu,
                         String location, byte[] corps, Instant expireLe) {
        boolean terminee() {
            return "TERMINEE".equals(etat);
        }
    }

    /**
     * Réserve la clé pour cet appelant.
     *
     * @return l'identifiant de la réservation, ou vide si la clé est déjà connue
     */
    public Optional<UUID> reserver(UUID applicationId, String appelant, UUID cle, String methode, String chemin,
                                   String empreinte, Instant maintenant, Instant expireLe) {
        UUID id = UuidV7.suivant();
        // ON CONFLICT plutôt qu'une exception de doublon : une erreur SQL
        // annulerait la transaction englobante, s'il y en a une.
        int n = jdbc.update("""
                INSERT INTO idempotence_cle (id, application_id, appelant, cle, methode, chemin,
                                             empreinte_requete, etat, cree_le, expire_le)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'EN_COURS', ?, ?)
                ON CONFLICT ON CONSTRAINT uk_idempotence_cle_appelant_cle DO NOTHING""",
                id, applicationId, appelant, cle, methode, chemin, empreinte,
                Timestamp.from(maintenant), Timestamp.from(expireLe));
        return n == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<Entree> lire(String appelant, UUID cle) {
        List<Entree> l = jdbc.query("""
                SELECT id, empreinte_requete, etat, statut_reponse, type_contenu, entete_location, corps_reponse, expire_le
                  FROM idempotence_cle WHERE appelant = ? AND cle = ?""",
                (rs, i) -> new Entree(rs.getObject("id", UUID.class), rs.getString("empreinte_requete").trim(),
                        rs.getString("etat"), (Integer) rs.getObject("statut_reponse"), rs.getString("type_contenu"),
                        rs.getString("entete_location"), rs.getBytes("corps_reponse"),
                        rs.getTimestamp("expire_le").toInstant()),
                appelant, cle);
        return l.stream().findFirst();
    }

    public void terminer(UUID id, int statut, String typeContenu, String location, byte[] corps) {
        jdbc.update("""
                UPDATE idempotence_cle SET etat = 'TERMINEE', statut_reponse = ?, type_contenu = ?,
                       entete_location = ?, corps_reponse = ? WHERE id = ?""",
                statut, typeContenu, location, corps, id);
    }

    /** Libère une réservation dont l'opération a échoué : l'appelant peut rejouer. */
    public void liberer(UUID id) {
        jdbc.update("DELETE FROM idempotence_cle WHERE id = ?", id);
    }

    /** Supprime les entrées expirées ; rend le nombre supprimé. */
    public int purger(Instant maintenant) {
        return jdbc.update("DELETE FROM idempotence_cle WHERE expire_le < ?", Timestamp.from(maintenant));
    }
}
