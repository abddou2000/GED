package com.ipt.ged.workflow.api;

import com.ipt.ged.security.UtilisateurConnecte;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implémentation livrée d'{@link AccesApiWorkflow} : l'acteur est
 * l'utilisateur authentifié ; aucune portée de clé à contrôler. Remplacée par
 * celle du lot clés d'API (dev2), qui s'y adosse pour les utilisateurs.
 */
@Component
public class AccesApiWorkflowUtilisateurs implements AccesApiWorkflow {

    @Override
    public ActeurWorkflow acteur(Authentication authentification, HttpServletRequest requete) {
        if (authentification != null && authentification.getPrincipal() instanceof UtilisateurConnecte u) {
            return new ActeurWorkflow(u.getUtilisateurId(), u.getEmployeId(), null, u.getNomComplet());
        }
        throw new AccessDeniedException("Opération de workflow réservée à une personne nommée");
    }

    @Override
    public void verifierPortee(Authentication authentification, OperationWorkflow operation, UUID noeudId) {
        // Aucune portée de clé pour un utilisateur : ses droits sont ceux d'AccessPredicate.
    }
}
