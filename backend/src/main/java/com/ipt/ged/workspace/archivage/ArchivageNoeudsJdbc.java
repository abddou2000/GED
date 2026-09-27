package com.ipt.ged.workspace.archivage;

import com.ipt.ged.common.StatutConservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implémentation du contrat {@link ArchivageNoeuds} sur le chemin matérialisé :
 * une instruction par opération pour toute la sous-arborescence (§12.5).
 */
@Service
public class ArchivageNoeudsJdbc implements ArchivageNoeuds {

    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    public ArchivageNoeudsJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String chemin(UUID noeudId) {
        List<String> l = jdbc.queryForList("SELECT chemin FROM noeud WHERE id = ?", String.class, noeudId);
        if (l.isEmpty()) throw new EntityNotFoundException("Espace de travail introuvable : " + noeudId);
        return l.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public StatutConservation statut(UUID noeudId) {
        List<String> l = jdbc.queryForList("SELECT statut_conservation FROM noeud WHERE id = ?", String.class, noeudId);
        if (l.isEmpty()) throw new EntityNotFoundException("Espace de travail introuvable : " + noeudId);
        return StatutConservation.valueOf(l.get(0));
    }

    @Override
    @Transactional
    public int marquerArchive(UUID noeudId, UUID auteurUtilisateurId) {
        em.flush();
        int n = jdbc.update("UPDATE noeud SET statut_conservation = 'ARCHIVE', archive_le = now(), archive_par = ?"
                + " WHERE chemin LIKE ? AND statut_conservation = 'ACTIF'", auteurUtilisateurId, chemin(noeudId) + "%");
        em.clear();
        return n;
    }

    @Override
    @Transactional
    public int marquerActif(UUID noeudId) {
        em.flush();
        int n = jdbc.update("UPDATE noeud SET statut_conservation = 'ACTIF', archive_le = NULL, archive_par = NULL"
                + " WHERE chemin LIKE ? AND statut_conservation = 'ARCHIVE'", chemin(noeudId) + "%");
        em.clear();
        return n;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> documentsAArchiver(UUID noeudId, UUID apres, int taille) {
        String prefixe = chemin(noeudId) + "%";
        String sql = "SELECT d.id FROM document d JOIN noeud n ON n.id = d.noeud_principal_id"
                + " WHERE n.chemin LIKE ? AND d.supprime = false AND d.statut_conservation = 'ACTIF'"
                + (apres != null ? " AND d.id > ?" : "") + " ORDER BY d.id LIMIT ?";
        return apres != null
                ? jdbc.queryForList(sql, UUID.class, prefixe, apres, Math.max(1, taille))
                : jdbc.queryForList(sql, UUID.class, prefixe, Math.max(1, taille));
    }
}
