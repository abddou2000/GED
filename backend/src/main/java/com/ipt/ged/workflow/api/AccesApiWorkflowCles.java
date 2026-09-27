package com.ipt.ged.workflow.api;

import com.ipt.ged.cleapi.ApplicationAuthentifiee;
import com.ipt.ged.cleapi.ControlePorteeApplication;
import com.ipt.ged.cleapi.OperationApi;
import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.security.UtilisateurConnecte;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Branchement du workflow sur le lot clés d'API (dev2, D8) : une application
 * n'agit dans le workflow que <b>pour le compte d'une personne nommée</b>
 * (en-tête {@code X-On-Behalf-Of}, résolu par {@code FiltreCleApi}), et dans
 * la <b>portée de sa clé</b> sur le nœud concerné ({@link OperationApi#WORKFLOW_PILOTAGE},
 * {@link OperationApi#WORKFLOW_DECISION}). Les droits appliqués ensuite sont
 * ceux de la personne ({@code AccessPredicate}) ; l'application est tracée en
 * plus (double identité). Un utilisateur de l'interface passe par
 * {@link AccesApiWorkflowUtilisateurs}.
 */
@Component
@Primary
public class AccesApiWorkflowCles implements AccesApiWorkflow {

    /** Code du refus d'une application qui n'agit pour personne. */
    public static final String DELEGATION_REQUISE = "DELEGATION_REQUISE";

    private final AccesApiWorkflowUtilisateurs utilisateurs;
    private final ControlePorteeApplication portee;

    public AccesApiWorkflowCles(AccesApiWorkflowUtilisateurs utilisateurs, ControlePorteeApplication portee) {
        this.utilisateurs = utilisateurs;
        this.portee = portee;
    }

    @Override
    public ActeurWorkflow acteur(Authentication authentification, HttpServletRequest requete) {
        if (authentification instanceof ApplicationAuthentifiee application) {
            UtilisateurConnecte u = application.deleguee();
            if (u == null) {
                throw new AccesRefuseException(DELEGATION_REQUISE,
                        "Une opération de workflow est faite au nom d'une personne : en-tête X-On-Behalf-Of requis.");
            }
            return new ActeurWorkflow(u.getUtilisateurId(), u.getEmployeId(), application.applicationId(),
                    u.getNomComplet());
        }
        return utilisateurs.acteur(authentification, requete);
    }

    @Override
    public void verifierPortee(Authentication authentification, OperationWorkflow operation, UUID noeudId) {
        if (!(authentification instanceof ApplicationAuthentifiee application) || noeudId == null) return;
        portee.verifier(application, operation == OperationWorkflow.DECISION
                ? OperationApi.WORKFLOW_DECISION : OperationApi.WORKFLOW_PILOTAGE, noeudId);
    }
}
