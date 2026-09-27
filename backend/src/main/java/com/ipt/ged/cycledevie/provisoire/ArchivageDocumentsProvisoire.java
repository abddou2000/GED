package com.ipt.ged.cycledevie.provisoire;

import com.ipt.ged.cycledevie.ErreurCycleDeVie;
import com.ipt.ged.document.archivage.ArchivageDocuments;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implémentation <b>provisoire</b> du contrat {@link ArchivageDocuments} de dev1
 * (lot modèle E7), même comportement : dans la transaction de l'appelant,
 * idempotente, 409 {@code DOCUMENT_VERROUILLE} sur un document verrouillé.
 *
 * <p>Déclarée seulement en l'absence d'une autre implémentation : à la fusion,
 * {@code ArchivageDocumentsJdbc} de dev1 la remplace, et cette classe est à
 * supprimer.
 */
public class ArchivageDocumentsProvisoire implements ArchivageDocuments {

    private final JdbcTemplate jdbc;

    public ArchivageDocumentsProvisoire(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean archiver(UUID documentId, UUID auteurUtilisateurId) {
        List<Boolean> verrou = jdbc.queryForList("SELECT is_locked FROM document WHERE id = ?", Boolean.class, documentId);
        if (verrou.isEmpty()) throw new EntityNotFoundException("Document introuvable : " + documentId);
        if (Boolean.TRUE.equals(verrou.get(0))) {
            throw new ErreurCycleDeVie(HttpStatus.CONFLICT, ErreurCycleDeVie.DOCUMENT_VERROUILLE,
                    "Document verrouillé : aucune modification tant que le verrou tient.");
        }
        return jdbc.update("UPDATE document SET statut_conservation = 'ARCHIVE', archive_le = ?, archive_par = ? "
                + "WHERE id = ? AND statut_conservation = 'ACTIF'",
                Timestamp.from(Instant.now()), auteurUtilisateurId, documentId) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean desarchiver(UUID documentId) {
        return jdbc.update("UPDATE document SET statut_conservation = 'ACTIF', archive_le = NULL, archive_par = NULL "
                + "WHERE id = ? AND statut_conservation = 'ARCHIVE'", documentId) == 1;
    }
}
