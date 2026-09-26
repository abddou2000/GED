package com.ipt.ged.recherche;

import org.springframework.security.core.Authentication;

/**
 * Prédicat provisoire, aligné sur l'application actuelle (un seul compte
 * authentifié, qui voit tout) : tout pour un utilisateur authentifié, rien
 * sinon. À remplacer par le point unique de droits du lot autorisation
 * (voir {@link PredicatDroits}).
 */
public class PredicatDroitsProvisoire implements PredicatDroits {

    @Override
    public FragmentSql predicat(String colonneDocumentId, Authentication utilisateur) {
        return utilisateur != null && utilisateur.isAuthenticated() ? FragmentSql.VRAI : FragmentSql.FAUX;
    }
}
