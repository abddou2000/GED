package com.ipt.ged.fichier.cles;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DepotClesFichier} en JDBC sur la table {@code cle_fichier} créée par
 * le changeset {@code 202609261200_cle_fichier.xml}.
 *
 * <p>JDBC plutôt qu'une entité : la table n'a ni relation ni cycle de vie
 * métier, et ce choix laisse le modèle JPA (en cours de migration vers UUID)
 * intact. Les requêtes sont paramétrées ; aucune ne concatène de valeur.
 */
public class DepotClesFichierJdbc implements DepotClesFichier {

    private static final RowMapper<CleFichier> LIGNE = (rs, i) -> new CleFichier(
            rs.getObject("id", UUID.class),
            rs.getBytes("dek_enveloppee"),
            rs.getString("kek_id"),
            rs.getString("algorithme"));

    private final JdbcTemplate jdbc;

    public DepotClesFichierJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enregistrer(CleFichier cle) {
        jdbc.update("INSERT INTO cle_fichier (id, dek_enveloppee, kek_id, algorithme) VALUES (?, ?, ?, ?)",
                cle.id(), cle.dekEnveloppee(), cle.kekId(), cle.algorithme());
    }

    @Override
    public Optional<CleFichier> trouver(UUID id) {
        return jdbc.query("SELECT id, dek_enveloppee, kek_id, algorithme FROM cle_fichier WHERE id = ?", LIGNE, id)
                .stream().findFirst();
    }

    @Override
    public boolean supprimer(UUID id) {
        return jdbc.update("DELETE FROM cle_fichier WHERE id = ?", id) > 0;
    }

    @Override
    public List<CleFichier> lotHorsKek(String kekActive, UUID apres, int taille) {
        if (apres == null) {
            return jdbc.query("SELECT id, dek_enveloppee, kek_id, algorithme FROM cle_fichier "
                    + "WHERE kek_id <> ? ORDER BY id LIMIT ?", LIGNE, kekActive, taille);
        }
        return jdbc.query("SELECT id, dek_enveloppee, kek_id, algorithme FROM cle_fichier "
                + "WHERE kek_id <> ? AND id > ? ORDER BY id LIMIT ?", LIGNE, kekActive, apres, taille);
    }

    @Override
    public boolean remplacerEnveloppe(UUID id, String ancienneKek, CleEnveloppee nouvelle) {
        return jdbc.update("UPDATE cle_fichier SET dek_enveloppee = ?, kek_id = ?, modifie_le = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND kek_id = ?",
                nouvelle.octets(), nouvelle.kekId(), id, ancienneKek) > 0;
    }

    @Override
    public long compterParKek(String kekId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cle_fichier WHERE kek_id = ?", Long.class, kekId);
        return n != null ? n : 0;
    }
}
