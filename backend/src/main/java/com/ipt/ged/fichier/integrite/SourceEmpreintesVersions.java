package com.ipt.ged.fichier.integrite;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Empreintes enregistrées de toutes les versions chiffrées
 * ({@code version_document.cle_fichier_id}, {@code empreinte}), parcourues par
 * pages sur la clé (§6.1.4, vérification mensuelle) : la mémoire reste bornée
 * quelle que soit la taille du fonds.
 */
public class SourceEmpreintesVersions implements SourceEmpreintes {

    private static final int PAGE = 500;

    private final JdbcTemplate jdbc;

    public SourceEmpreintesVersions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void parcourir(Consumer<EmpreinteAttendue> visiteur) {
        UUID curseur = null;
        List<Ligne> page;
        do {
            page = curseur == null
                    ? jdbc.query("SELECT id, cle_fichier_id, empreinte FROM version_document "
                            + "WHERE cle_fichier_id IS NOT NULL ORDER BY id LIMIT ?", LIGNE, PAGE)
                    : jdbc.query("SELECT id, cle_fichier_id, empreinte FROM version_document "
                            + "WHERE cle_fichier_id IS NOT NULL AND id > ? ORDER BY id LIMIT ?", LIGNE, curseur, PAGE);
            for (Ligne l : page) {
                curseur = l.versionId();
                visiteur.accept(new EmpreinteAttendue(l.fichierId(), l.empreinte(), "version " + l.versionId()));
            }
        } while (page.size() == PAGE);
    }

    private record Ligne(UUID versionId, UUID fichierId, String empreinte) {
    }

    private static final org.springframework.jdbc.core.RowMapper<Ligne> LIGNE = (rs, i) -> new Ligne(
            rs.getObject("id", UUID.class), rs.getObject("cle_fichier_id", UUID.class), rs.getString("empreinte"));
}
