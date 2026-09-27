package com.ipt.ged.fichier.previsualisation;

import org.springframework.security.core.Authentication;

/**
 * <b>Point d'extension du lot autorisation.</b>
 *
 * <p>La prévisualisation applique les mêmes contrôles de droits et de
 * confidentialité que le téléchargement (§6.1.6). Ces contrôles relèvent du
 * point d'application unique des droits, livré par le lot autorisation : il
 * remplacera l'implémentation provisoire par une
 * implémentation qui délègue à ce point unique (objet hors périmètre → 404,
 * refus → 403).
 */
public interface ControleAccesPrevisualisation {

    /**
     * Lève une exception si l'utilisateur ne peut pas lire cette version.
     *
     * @throws org.springframework.security.access.AccessDeniedException refus (403) ;
     * @throws com.ipt.ged.fichier.ErreurFichierException 404 pour un objet hors
     *         périmètre, afin de ne pas révéler son existence.
     */
    void verifierLecture(ResolveurFichierVersion.FichierVersion version, Authentication utilisateur);
}
