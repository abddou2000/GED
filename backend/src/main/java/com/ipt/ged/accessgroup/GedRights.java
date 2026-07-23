package com.ipt.ged.accessgroup;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Les 8 droits fins d'un groupe d'accès (repris du {@code DroitsGed} de CCISTTA).
 *
 * <p>{@link #normalize()} impose la cohérence des dépendances façon « explorateur
 * Windows » : un droit fort implique tous les droits plus faibles
 * (supprimer ⟹ modifier ⟹ uploadé ⟹ lecture ⟹ accès).</p>
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

    /** Ferme les dépendances : tout droit fort réactive les droits qu'il présuppose. */
    public void normalize() {
        if (supprimer || deplacer) {
            modifier = true;
        }
        if (modifier || ajouterVersion) {
            uploader = true;
        }
        if (uploader) {
            lecture = true;
        }
        if (lecture || modifier || uploader || supprimer || deplacer || ajouterVersion || verrouillerDeverrouiller) {
            access = true;
        }
    }
}
