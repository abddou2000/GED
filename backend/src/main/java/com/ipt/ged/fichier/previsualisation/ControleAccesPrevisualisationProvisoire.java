package com.ipt.ged.fichier.previsualisation;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * Contrôle provisoire : aligné sur le téléchargement actuel, qui n'exige
 * qu'un utilisateur authentifié. À remplacer par le lot autorisation (voir
 * {@link ControleAccesPrevisualisation}).
 */
public class ControleAccesPrevisualisationProvisoire implements ControleAccesPrevisualisation {

    @Override
    public void verifierLecture(ResolveurFichierVersion.FichierVersion version, Authentication utilisateur) {
        if (utilisateur == null || !utilisateur.isAuthenticated()) {
            throw new AccessDeniedException("Authentification requise");
        }
    }
}
