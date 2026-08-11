package com.ipt.ged.accessgroup;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Colonnes inertes héritées de l'ancien modèle de droits fins. Aucune autorisation
 * ne s'y appuie : l'application n'a qu'un utilisateur, l'administrateur, à qui tout
 * est ouvert. Elles ne sont conservées que pour préserver les données déjà
 * enregistrées, reprenables si le produit passe un jour à plusieurs utilisateurs.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class GedRights {

    @Column(name = "droit_access", nullable = false)
    private boolean access = false;

    @Column(name = "droit_lecture", nullable = false)
    private boolean lecture = false;

    @Column(name = "droit_modifier", nullable = false)
    private boolean modifier = false;

    @Column(name = "droit_uploader", nullable = false)
    private boolean uploader = false;

    @Column(name = "droit_supprimer", nullable = false)
    private boolean supprimer = false;

    @Column(name = "droit_deplacer", nullable = false)
    private boolean deplacer = false;

    @Column(name = "droit_ajouter_version", nullable = false)
    private boolean ajouterVersion = false;

    @Column(name = "droit_verrouiller_deverrouiller", nullable = false)
    private boolean verrouillerDeverrouiller = false;
}
