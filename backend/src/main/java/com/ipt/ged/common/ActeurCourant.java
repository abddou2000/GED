package com.ipt.ged.common;

import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Employé à l'origine de la requête en cours, lu dans le contexte de sécurité.
 *
 * <p>Sert à renseigner l'auteur d'une écriture (suppression douce) sans faire
 * transiter l'identité par chaque signature de méthode. L'identité vient
 * toujours du jeton vérifié, jamais d'un paramètre fourni par l'appelant.
 */
public final class ActeurCourant {

    private ActeurCourant() {}

    /**
     * @return l'identifiant de l'employé authentifié, ou {@code null} hors
     *         requête authentifiée (tâche d'amorçage, traitement technique).
     */
    public static UUID employeId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UtilisateurConnecte u) {
            return u.getEmployeId();
        }
        return null;
    }

    /**
     * @return l'identité GED ({@code utilisateur.id}) authentifiée, ou
     *         {@code null} hors requête authentifiée. Auteur des attributions
     *         et des événements d'audit des droits.
     */
    public static UUID utilisateurId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UtilisateurConnecte u) {
            return u.getUtilisateurId();
        }
        return null;
    }
}
