package com.ipt.ged.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Événement d'audit construit par le code qui audite directement (services
 * des référentiels) :
 * <pre>{@code
 * audit.enregistrer(EntreeAudit.de(ActionAudit.TYPE_DOCUMENT_MODIFIE, "TYPE_DOCUMENT", id)
 *         .avecAvantApres(avant, apres));
 * }</pre>
 */
public record EntreeAudit(String action, String objetType, UUID objetId,
                          Map<String, Object> avant, Map<String, Object> apres,
                          ResultatAudit resultat, String motif,
                          UUID acteurUtilisateurId, UUID acteurApplicationId,
                          String acteurNom, String adresseIp) implements EvenementAudit {

    public EntreeAudit {
        Objects.requireNonNull(action, "action");
        resultat = resultat == null ? ResultatAudit.SUCCES : resultat;
    }

    public static EntreeAudit de(ActionAudit action, String objetType, UUID objetId) {
        return new EntreeAudit(action.code(), objetType, objetId, null, null,
                ResultatAudit.SUCCES, null, null, null, null, null);
    }

    public static EntreeAudit de(ActionAudit action) {
        return de(action, null, null);
    }

    public EntreeAudit avecAvantApres(Map<String, Object> nouvelAvant, Map<String, Object> nouvelApres) {
        return new EntreeAudit(action, objetType, objetId, nouvelAvant, nouvelApres, resultat, motif,
                acteurUtilisateurId, acteurApplicationId, acteurNom, adresseIp);
    }

    public EntreeAudit avecApres(Map<String, Object> nouvelApres) {
        return avecAvantApres(avant, nouvelApres);
    }

    public EntreeAudit avecAvant(Map<String, Object> nouvelAvant) {
        return avecAvantApres(nouvelAvant, apres);
    }

    public EntreeAudit refus(String nouveauMotif) {
        return new EntreeAudit(action, objetType, objetId, avant, apres, ResultatAudit.REFUS, nouveauMotif,
                acteurUtilisateurId, acteurApplicationId, acteurNom, adresseIp);
    }

    public EntreeAudit echec(String nouveauMotif) {
        return new EntreeAudit(action, objetType, objetId, avant, apres, ResultatAudit.ECHEC, nouveauMotif,
                acteurUtilisateurId, acteurApplicationId, acteurNom, adresseIp);
    }

    public EntreeAudit avecMotif(String nouveauMotif) {
        return new EntreeAudit(action, objetType, objetId, avant, apres, resultat, nouveauMotif,
                acteurUtilisateurId, acteurApplicationId, acteurNom, adresseIp);
    }

    /** Acteur explicite : traitement pour le compte d'un utilisateur, connexion. */
    public EntreeAudit parActeur(UUID utilisateurId, UUID applicationId, String nom, String ip) {
        return new EntreeAudit(action, objetType, objetId, avant, apres, resultat, motif,
                utilisateurId, applicationId, nom, ip);
    }
}
