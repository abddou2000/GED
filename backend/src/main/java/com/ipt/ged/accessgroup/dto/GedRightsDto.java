package com.ipt.ged.accessgroup.dto;

import com.ipt.ged.accessgroup.GedRights;

/**
 * Les 8 droits d'un groupe, échangés avec le frontend.
 */
public record GedRightsDto(
        boolean access,
        boolean lecture,
        boolean modifier,
        boolean uploader,
        boolean supprimer,
        boolean deplacer,
        boolean ajouterVersion,
        boolean verrouillerDeverrouiller
) {
    public static GedRightsDto from(GedRights r) {
        return new GedRightsDto(
                r.isAccess(), r.isLecture(), r.isModifier(), r.isUploader(),
                r.isSupprimer(), r.isDeplacer(), r.isAjouterVersion(), r.isVerrouillerDeverrouiller());
    }

    /** Recopie ces droits dans l'entité embarquée. */
    public void applyTo(GedRights r) {
        r.setAccess(access);
        r.setLecture(lecture);
        r.setModifier(modifier);
        r.setUploader(uploader);
        r.setSupprimer(supprimer);
        r.setDeplacer(deplacer);
        r.setAjouterVersion(ajouterVersion);
        r.setVerrouillerDeverrouiller(verrouillerDeverrouiller);
    }
}
