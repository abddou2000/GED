package com.ipt.ged.document.version;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.document.DocumentVersion;
import com.ipt.ged.document.DocumentVersionRepository;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.modele.EvenementModeleDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Contrat pour le dépôt chiffré de dev3 : appeler {@link #verser} avec la
 * version construite (fichier, empreinte), le document ayant été chargé avec
 * son verrou d'écriture ({@code findByIdPourEcriture}).
 */
@Service
public class ServiceVersions {

    private final DocumentVersionRepository versions;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher evenements;

    @PersistenceContext
    private EntityManager em;

    public ServiceVersions(DocumentVersionRepository versions, JdbcTemplate jdbc, ApplicationEventPublisher evenements) {
        this.versions = versions;
        this.jdbc = jdbc;
        this.evenements = evenements;
    }

    /**
     * Enregistre une version comme COURANTE (versement, D9). La première
     * version d'un document (dépôt) n'est pas un événement de versement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentVersion verser(UploadDocument d, DocumentVersion v) {
        Integer max = jdbc.queryForObject("SELECT max(numero) FROM version_document WHERE document_id = ?",
                Integer.class, d.getId());
        int numero = max == null ? 1 : max + 1;
        Map<String, Object> avant = courante(d.getId());
        demettre(d.getId());
        v.setNumero(numero);
        v.setAuteurId(ActeurCourant.utilisateurId());
        v.setPrincipale(true);
        DocumentVersion enregistree = versions.saveAndFlush(v);
        d.getVersions().add(0, enregistree);
        if (numero > 1) {
            evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.VERSION_AJOUTEE, d.getId(),
                    avant, instantane(enregistree), enregistree.getObservation(), ActeurCourant.utilisateurId()));
        }
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
        Map<String, Object> avant = courante(d.getId());
        demettre(d.getId());
        cible.setPrincipale(true);
        versions.saveAndFlush(cible);
        evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.VERSION_RESTAUREE, d.getId(),
                avant, instantane(cible), null, ActeurCourant.utilisateurId()));
        return cible;
    }

    /** Démet la version courante, et écrit la démission avant toute promotion. */
    private void demettre(UUID documentId) {
        versions.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId).forEach(x -> x.setPrincipale(false));
        em.flush();
    }

    private Map<String, Object> courante(UUID documentId) {
        return versions.findByDocumentIdAndPrincipaleTrueOrderByIdDesc(documentId).stream().findFirst()
                .map(ServiceVersions::instantane).orElse(null);
    }

    private static Map<String, Object> instantane(DocumentVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", v.getId());
        m.put("numero", v.getNumero());
        m.put("fichier", v.getFileName());
        m.put("empreinte", v.getEmpreinte());
        return m;
    }
}
