package com.ipt.ged.document.archivage;

import com.ipt.ged.document.GardeEcriture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implémentation du contrat {@link ArchivageDocuments} : une seule instruction
 * SQL conditionnelle par appel, donc sûre en concurrence (deux jobs qui
 * archivent le même document n'écrivent qu'une fois).
 */
@Service
public class ArchivageDocumentsJdbc implements ArchivageDocuments {

    private final JdbcTemplate jdbc;
    private final GardeEcriture garde;

    @PersistenceContext
    private EntityManager em;

    public ArchivageDocumentsJdbc(JdbcTemplate jdbc, GardeEcriture garde) {
        this.jdbc = jdbc;
        this.garde = garde;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean archiver(UUID documentId, UUID auteurUtilisateurId) {
        em.flush();
        garde.exigerNonVerrouille(documentId);
        int n = jdbc.update("UPDATE document SET statut_conservation = 'ARCHIVE', archive_le = now(), archive_par = ?"
                + " WHERE id = ? AND statut_conservation = 'ACTIF'", auteurUtilisateurId, documentId);
        em.clear();
        return n == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean desarchiver(UUID documentId) {
        em.flush();
        int n = jdbc.update("UPDATE document SET statut_conservation = 'ACTIF', archive_le = NULL, archive_par = NULL"
                + " WHERE id = ? AND statut_conservation = 'ARCHIVE'", documentId);
        if (n == 0 && jdbc.queryForObject("SELECT count(*) FROM document WHERE id = ?", Integer.class, documentId) == 0) {
            throw new EntityNotFoundException("Document introuvable : " + documentId);
        }
        em.clear();
        return n == 1;
    }
}
