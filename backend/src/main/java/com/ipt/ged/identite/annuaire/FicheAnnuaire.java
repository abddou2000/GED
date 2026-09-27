package com.ipt.ged.identite.annuaire;

import java.util.UUID;

/**
 * Ce que la GED lit d'une personne dans l'annuaire : le strict minimum (décision
 * client D3). Aucun attribut d'appartenance (principe P2) ni d'état du compte
 * (décision D1) : ils ne sont même pas demandés au serveur.
 *
 * @param objectGuid  clé technique immuable de l'annuaire
 * @param identifiant {@code sAMAccountName}, seul identifiant de connexion (D2)
 * @param courriel    {@code mail}, pour les notifications uniquement
 * @param direction   {@code department}, si renseigné
 */
public record FicheAnnuaire(UUID objectGuid, String identifiant, String prenom, String nom,
                            String nomAffiche, String courriel, String direction) {

    /** Nom lisible : l'affichage de l'annuaire, sinon prénom et nom, sinon l'identifiant. */
    public String nomComplet() {
        if (nomAffiche != null && !nomAffiche.isBlank()) return nomAffiche.trim();
        String compose = ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
        return compose.isEmpty() ? identifiant : compose;
    }
}
