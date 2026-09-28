package com.ipt.ged.workflow.circuit;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.PermissionRefuseeException;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.api.ActeurWorkflow;
import com.ipt.ged.workflow.circuit.evenement.EvenementWorkflow;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rattachement d'une règle de workflow à un nœud (espace ou dossier) ou à un
 * type de document (§12.8). Modifiable à tout moment : les circuits déjà
 * ouverts sont des copies figées, seuls les dépôts futurs sont concernés.
 * Réservé à qui gère les référentiels.
 */
@Service
public class RattachementRegles {

    public static final String REGLE_RATTACHEE = "REGLE_WORKFLOW_RATTACHEE";

    private final WorkSpaceRepository noeuds;
    private final TypeDocumentRepository types;
    private final WorkflowRepository regles;
    private final AccessPredicate predicat;
    private final ApplicationEventPublisher evenements;

    public RattachementRegles(WorkSpaceRepository noeuds, TypeDocumentRepository types, WorkflowRepository regles,
                              AccessPredicate predicat, ApplicationEventPublisher evenements) {
        this.noeuds = noeuds;
        this.types = types;
        this.regles = regles;
        this.predicat = predicat;
        this.evenements = evenements;
    }

    @Transactional
    public void rattacherNoeud(UUID noeudId, UUID regleId, ActeurWorkflow acteur) {
        exigerReferentiels(acteur);
        WorkSpace n = noeuds.findById(noeudId)
                .orElseThrow(() -> new EntityNotFoundException("Nœud introuvable : " + noeudId));
        WorkflowGed avant = n.getWorkflow();
        n.setWorkflow(regle(regleId));
        publier("NOEUD", noeudId, avant, n.getWorkflow(), acteur);
    }

    @Transactional
    public void rattacherType(UUID typeId, UUID regleId, ActeurWorkflow acteur) {
        exigerReferentiels(acteur);
        TypeDocument t = types.findById(typeId)
                .orElseThrow(() -> new EntityNotFoundException("Type de document introuvable : " + typeId));
        WorkflowGed avant = t.getRegleWorkflow();
        t.setRegleWorkflow(regle(regleId));
        publier("TYPE_DOCUMENT", typeId, avant, t.getRegleWorkflow(), acteur);
    }

    private WorkflowGed regle(UUID regleId) {
        if (regleId == null) return null;
        WorkflowGed r = regles.findById(regleId)
                .orElseThrow(() -> new EntityNotFoundException("Règle de workflow introuvable : " + regleId));
        if (r.isSupprime()) {
            throw new IllegalArgumentException("Cette règle est en corbeille : restaurez-la avant de la rattacher.");
        }
        return r;
    }

    private void exigerReferentiels(ActeurWorkflow acteur) {
        var d = predicat.droits(new Sujet(TypeSujet.UTILISATEUR, acteur.utilisateurId(), acteur.employeId(),
                acteur.libelle()));
        if (!d.administre(CodePermission.GERER_REFERENTIELS)) {
            throw new PermissionRefuseeException("Permission d'administration « GERER_REFERENTIELS » requise");
        }
    }

    private void publier(String objetType, UUID objetId, WorkflowGed avant, WorkflowGed apres, ActeurWorkflow a) {
        Map<String, Object> av = new HashMap<>();
        av.put("regleId", avant != null ? avant.getId() : null);
        Map<String, Object> ap = new HashMap<>();
        ap.put("regleId", apres != null ? apres.getId() : null);
        evenements.publishEvent(new EvenementWorkflow(REGLE_RATTACHEE, objetId, objetType, av, ap, null,
                a.utilisateurId(), a.applicationId(), a.libelle(), null, Instant.now()));
    }
}
