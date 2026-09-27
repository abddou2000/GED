package com.ipt.ged.autorisation;

import com.ipt.ged.security.UtilisateurConnecte;

import java.util.Objects;
import java.util.UUID;

/**
 * Sujet d'une décision d'autorisation (dossier technique §12.2.1) : une
 * identité GED ou une application. Les groupes GED ne sont pas des appelants :
 * ils interviennent par les attributions de leurs membres.
 *
 * @param type      {@link TypeSujet#UTILISATEUR} ou {@link TypeSujet#APPLICATION}
 * @param id        identité GED ({@code utilisateur.id}) ou application
 * @param employeId personne métier (déposant d'un document privé) ; {@code null}
 *                  pour une application
 * @param libelle   nom lisible, pour l'écran des droits effectifs
 */
public record Sujet(TypeSujet type, UUID id, UUID employeId, String libelle) {

    public Sujet {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        if (type == TypeSujet.GROUPE) {
            throw new IllegalArgumentException("Un groupe n'est pas un appelant");
        }
    }

    public static Sujet utilisateur(UtilisateurConnecte u) {
        return new Sujet(TypeSujet.UTILISATEUR, u.getUtilisateurId(), u.getEmployeId(), u.getNomComplet());
    }

    /** Clé du cache des droits résolus. */
    String cle() {
        return type + ":" + id;
    }
}
