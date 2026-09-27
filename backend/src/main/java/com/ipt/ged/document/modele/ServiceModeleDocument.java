package com.ipt.ged.document.modele;

import com.ipt.ged.autorisation.ConflitAutorisationException;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.Limites;
import com.ipt.ged.document.DocumentRattachementRepository;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.planindexation.metamodele.DefinitionPlan;
import com.ipt.ged.planindexation.metamodele.PlanIndexationVersion;
import com.ipt.ged.planindexation.metamodele.ServiceVersionsPlan;
import com.ipt.ged.planindexation.metamodele.ValidateurMetadonnees;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.workspace.WorkSpace;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Modèle du document (lot E7) : socle commun, métadonnées validées contre la
 * version du plan, changement de type, déplacement et renommage (§12.5, §12.7).
 *
 * <p>Aucun contrôle de droit ni de verrou ici : le service appelant exige les
 * permissions (point d'application unique) et la garde d'écriture avant. Ces
 * méthodes appliquent les RÈGLES du modèle et publient les événements d'audit.
 */
@Service
public class ServiceModeleDocument {

    public static final String NOM_DEJA_UTILISE = "NOM_DEJA_UTILISE";

    private final ServiceVersionsPlan versionsPlan;
    private final DocumentRattachementRepository rattachements;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher evenements;

    public ServiceModeleDocument(ServiceVersionsPlan versionsPlan, DocumentRattachementRepository rattachements,
                                 JdbcTemplate jdbc, ApplicationEventPublisher evenements) {
        this.versionsPlan = versionsPlan;
        this.rattachements = rattachements;
        this.jdbc = jdbc;
        this.evenements = evenements;
    }

    /**
     * Au dépôt : version du plan en vigueur, métadonnées validées contre elle,
     * objet et date du document (date de dépôt si absente).
     *
     * @throws com.ipt.ged.planindexation.metamodele.MetadonneesInvalidesException avant toute écriture
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appliquerAuDepot(UploadDocument d, TypeDocument type, Map<String, ?> metadonnees, String objet,
                                 LocalDate dateDocument) {
        Optional<PlanIndexationVersion> version = versionsPlan.enVigueur(type);
        DefinitionPlan plan = version.map(versionsPlan::definition).orElse(DefinitionPlan.VIDE);
        // Jeu fourni = jeu complet (obligatoires exigés) ; rien de fourni =
        // dépôt en deux temps, indexation ultérieure (§12.11).
        boolean fournies = metadonnees != null && !metadonnees.isEmpty();
        d.setMetadonnees(new HashMap<>(ValidateurMetadonnees.valider(plan, metadonnees, fournies)));
        d.setPlanIndexationVersionId(version.map(PlanIndexationVersion::getId).orElse(null));
        d.setObjet(objet(objet));
        d.setDateDocument(dateDocument != null ? dateDocument : LocalDate.now());
    }

    /**
     * Remplace les métadonnées, validées contre la version de plan DU DOCUMENT
     * (celle de son dépôt) : modifier le plan n'altère pas les documents déjà
     * déposés. Un document antérieur au versionnement reçoit la version en
     * vigueur de son type.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void modifierMetadonnees(UploadDocument d, Map<String, ?> metadonnees) {
        DefinitionPlan plan = planDuDocument(d);
        Map<String, Object> avant = new LinkedHashMap<>(d.getMetadonnees());
        Map<String, Object> apres = ValidateurMetadonnees.valider(plan, metadonnees);
        if (!avant.equals(apres)) {
            d.setMetadonnees(new HashMap<>(apres));
            evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.METADONNEES_MODIFIEES,
                    d.getId(), avant, new LinkedHashMap<>(apres), null, ActeurCourant.utilisateurId()));
        }
    }

    /**
     * Change le type : la version en vigueur du NOUVEAU plan s'applique, et les
     * métadonnées (fournies, ou existantes à défaut) sont revalidées contre
     * elle. Pour transposer des champs d'un plan à l'autre, passer par la
     * re-typologisation ({@code job_retypage}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changerType(UploadDocument d, TypeDocument nouveau, Map<String, ?> metadonnees) {
        Optional<PlanIndexationVersion> version = versionsPlan.enVigueur(nouveau);
        DefinitionPlan plan = version.map(versionsPlan::definition).orElse(DefinitionPlan.VIDE);
        Map<String, ?> source = metadonnees != null ? metadonnees : d.getMetadonnees();
        // Sans nouveau jeu fourni, seules la nature et l'appartenance au plan
        // des valeurs existantes sont contrôlées.
        d.setMetadonnees(new HashMap<>(ValidateurMetadonnees.valider(plan, source, metadonnees != null)));
        d.setPlanIndexationVersionId(version.map(PlanIndexationVersion::getId).orElse(null));
        d.setTypeDocument(nouveau);
    }

    /** Définition du plan applicable au document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DefinitionPlan planDuDocument(UploadDocument d) {
        if (d.getPlanIndexationVersionId() != null) return versionsPlan.definition(d.getPlanIndexationVersionId());
        Optional<PlanIndexationVersion> version = versionsPlan.enVigueur(d.getTypeDocument());
        d.setPlanIndexationVersionId(version.map(PlanIndexationVersion::getId).orElse(null));
        return version.map(versionsPlan::definition).orElse(DefinitionPlan.VIDE);
    }

    /**
     * Renommage (§12.5) : nom unique parmi les documents vivants de
     * l'emplacement principal (409 {@link #NOM_DEJA_UTILISE}), audité avant /
     * après.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void renommer(UploadDocument d, String nom) {
        String nouveau = nom == null ? "" : nom.trim();
        if (nouveau.isEmpty()) throw new IllegalArgumentException("Le nom du document est obligatoire");
        Limites.controler(nouveau, "nom du document");
        if (nouveau.equals(d.getName())) return;
        Boolean pris = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document WHERE noeud_principal_id = ?"
                        + " AND lower(name) = lower(?) AND supprime = false AND id <> ?)", Boolean.class,
                d.getWorkspace().getId(), nouveau, d.getId());
        if (Boolean.TRUE.equals(pris)) {
            throw new ConflitAutorisationException(NOM_DEJA_UTILISE,
                    "Un document « " + nouveau + " » existe déjà dans ce dossier.");
        }
        String ancien = d.getName();
        d.setName(nouveau);
        evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.DOCUMENT_RENOMME, d.getId(),
                Map.of("nom", ancien), Map.of("nom", nouveau), null, ActeurCourant.utilisateurId()));
    }

    /**
     * Déplacement de l'emplacement principal (§12.4, §12.5) : les rattachements
     * complémentaires ne bougent pas, sauf celui qui visait la destination
     * (il ferait doublon avec le principal). Audité avec origine et destination.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deplacer(UploadDocument d, WorkSpace destination) {
        WorkSpace origine = d.getWorkspace();
        if (origine.getId().equals(destination.getId())) return;
        rattachements.findByDocumentIdAndNoeudId(d.getId(), destination.getId()).ifPresent(rattachements::delete);
        d.setWorkspace(destination);
        evenements.publishEvent(EvenementModeleDocument.succes(EvenementModeleDocument.DOCUMENT_DEPLACE, d.getId(),
                Map.of("noeudId", origine.getId(), "noeud", origine.getName()),
                Map.of("noeudId", destination.getId(), "noeud", destination.getName()), null,
                ActeurCourant.utilisateurId()));
    }

    private static String objet(String objet) {
        if (objet == null || objet.isBlank()) return null;
        String o = objet.trim();
        if (o.length() > 1000) throw new IllegalArgumentException("L'objet du document est limité à 1000 caractères.");
        return o;
    }

    /** Objet validé (fiche). */
    public static String objetValide(String objet) {
        return objet(objet);
    }
}
