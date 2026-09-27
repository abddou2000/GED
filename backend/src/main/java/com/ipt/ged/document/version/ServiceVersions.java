package com.ipt.ged.document.version;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.document.DocumentVersion;
import com.ipt.ged.document.DocumentVersionRepository;
import com.ipt.ged.document.UploadDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Versions d'un document (dossier technique §12.8, décision D9).
 *
 * <ul>
 *   <li><b>Versement</b> : la nouvelle version reçoit le numéro suivant et son
 *       auteur, et devient COURANTE ; l'ancienne reste dans l'historique, en
 *       lecture seule (déclencheur en base), consultable et téléchargeable.</li>
 *   <li><b>Désignation d'une version antérieure comme courante</b> (Q6 non
 *       tranchée : on garde le V3) : possible avec la permission Modifier,
 *       sans effacer les versions intermédiaires.</li>
 * </ul>
 * Une seule version courante par document : l'index unique partiel
 * {@code uk_version_document_courante} le garantit. L'ancienne courante est
 * démise et la démission ÉCRITE en base avant que la nouvelle ne soit promue
 * (Hibernate exécuterait sinon l'insertion avant la mise à jour).
 *
 * <p><b>Aucun événement n'est publié ici</b> : l'action appelante
 * ({@code DocumentService}) publie la sienne ({@code VersionAjoutee},
 * {@code VersionRestauree}, {@code DocumentDepose}), avec la clé du fichier
 * chiffré et l'état OCR qu'elle seule connaît — une publication par action.
 * Le document doit avoir été chargé avec son verrou d'écriture
 * ({@code findByIdPourEcriture}).
 */
@Service
public class ServiceVersions {

    private final DocumentVersionRepository versions;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    public ServiceVersions(DocumentVersionRepository versions, JdbcTemplate jdbc) {
        this.versions = versions;
        this.jdbc = jdbc;
    }

    /** Enregistre une version comme COURANTE, numérotée, avec son auteur (versement, D9). */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentVersion verser(UploadDocument d, DocumentVersion v) {
        Integer max = jdbc.queryForObject("SELECT max(numero) FROM version_document WHERE document_id = ?",
                Integer.class, d.getId());
        demettre(d.getId());
        v.setNumero(max == null ? 1 : max + 1);
        v.setAuteurId(ActeurCourant.utilisateurId());
        v.setPrincipale(true);
        DocumentVersion enregistree = versions.saveAndFlush(v);
        // Reportée en tête de la collection en mémoire : la réponse, et la
        // version courante vue par la suite de la transaction, en dépendent.
        d.getVersions().add(0, enregistree);
        return enregistree;
    }

    /** Désigne une version antérieure comme courante (Q6 : V3 conservé). */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentVersion designerCourante(UploadDocument d, UUID versionId) {
        DocumentVersion cible = versions.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version introuvable : " + versionId));
        if (!cible.getDocument().getId().equals(d.getId())) {
            throw new IllegalArgumentException("Cette version n'appartient pas au document");
        }
        if (cible.isPrincipale()) return cible;
        demettre(d.getId());
        cible.setPrincipale(true);
        return versions.saveAndFlush(cible);
    }

    /** Démet la version courante, et écrit la démission avant toute promotion. */
    private void demettre(UUID documentId) {
        versions.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId).forEach(x -> x.setPrincipale(false));
        em.flush();
    }
}
