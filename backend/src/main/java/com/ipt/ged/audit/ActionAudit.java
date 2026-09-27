package com.ipt.ged.audit;

/**
 * Catalogue des codes d'action du journal d'audit (DAT §7.4.1, dossier
 * fonctionnel §4.9.4, revue technique D11).
 *
 * <p>Un code est <b>stable</b> : il sert de filtre de consultation, de clé
 * d'export et de preuve (Article 49.11). On n'en renomme ni n'en supprime
 * jamais un ; un nouvel événement reçoit un nouveau code. Forme :
 * {@code OBJET_OPERATION}, majuscules et soulignés (contrainte
 * {@code ck_journal_audit_action}).
 *
 * <p>Les lots qui publient leurs propres événements ({@link EvenementAudit})
 * peuvent porter un code absent de cette liste : il est enregistré tel quel et
 * signalé au journal technique, pour être ajouté ici.
 */
public enum ActionAudit {

    // --- Authentification (§3.4.1 : réussites et échecs journalisés) ------
    CONNEXION_REUSSIE,
    CONNEXION_REFUSEE,
    DECONNEXION,
    SESSION_RENOUVELEE,
    SESSIONS_REVOQUEES,

    // --- Documents : consultation --------------------------------------------
    DOCUMENT_CONSULTE,
    APERCU_CONSULTE,
    DOCUMENT_TELECHARGE,
    DOCUMENT_EXPORTE,

    // --- Documents : écritures -----------------------------------------------
    DOCUMENT_DEPOSE,
    VERSION_AJOUTEE,
    VERSION_RESTAUREE,
    METADONNEES_MODIFIEES,
    INDEXATION_ENREGISTREE,
    DOCUMENT_DEPLACE,
    DOCUMENT_RENOMME,
    RATTACHEMENT_AJOUTE,
    RATTACHEMENT_RETIRE,
    DOCUMENT_VERROUILLE,
    DOCUMENT_DEVERROUILLE,
    DOCUMENT_ARCHIVE,
    DOCUMENT_DESARCHIVE,
    DOCUMENT_SUPPRIME,
    DOCUMENT_RESTAURE,
    DOCUMENT_PURGE,

    // --- Documents : traitements techniques et contrôles ----------------------
    CONTENU_INDEXE,
    OCR_ECHEC,
    FICHIER_INFECTE,
    INTEGRITE_ANOMALIE,

    // --- Droits (§3, §12.2) ----------------------------------------------------
    HABILITATION_MODIFIEE,
    ACCES_REFUSE,

    // --- Circuits de validation (§4.5, §12.8) --------------------------------
    VALIDATION_APPROUVEE,
    VALIDATION_REJETEE,
    VALIDATION_RELANCEE,
    CIRCUIT_ANNULE,

    // --- Administration : espaces et dossiers ----------------------------------
    ESPACE_CREE,
    ESPACE_MODIFIE,
    ESPACE_DEPLACE,
    ESPACE_ARCHIVE,
    ESPACE_DESARCHIVE,
    ESPACE_SUPPRIME,
    ESPACE_RESTAURE,

    // --- Administration : référentiels ---------------------------------------
    TYPE_DOCUMENT_CREE,
    TYPE_DOCUMENT_MODIFIE,
    TYPE_DOCUMENT_SUPPRIME,
    TYPE_DOCUMENT_RESTAURE,
    INDEX_CREE,
    INDEX_MODIFIE,
    INDEX_SUPPRIME,
    INDEX_RESTAURE,
    PLAN_INDEXATION_CREE,
    PLAN_INDEXATION_MODIFIE,
    PLAN_INDEXATION_SUPPRIME,
    PLAN_INDEXATION_RESTAURE,
    WORKFLOW_CREE,
    WORKFLOW_MODIFIE,
    WORKFLOW_SUPPRIME,
    WORKFLOW_RESTAURE,
    ETIQUETTE_CREEE,
    ETIQUETTE_MODIFIEE,
    ETIQUETTE_SUPPRIMEE,
    ETIQUETTE_RESTAUREE,
    GROUPE_CREE,
    GROUPE_MODIFIE,
    GROUPE_SUPPRIME,
    GROUPE_RESTAURE,
    EMPLOYE_CREE,
    EMPLOYE_MODIFIE,
    EMPLOYE_SUPPRIME,
    PROFIL_MODIFIE,

    // --- Intégration (§5) ----------------------------------------------------
    APPEL_API,
    CLE_API_GENEREE,
    CLE_API_REGENEREE,
    CLE_API_REVOQUEE,
    CLE_API_REFUSEE,
    QUOTA_DEPASSE,
    APPLICATION_CREEE,
    APPLICATION_MODIFIEE,
    APPLICATION_ACTIVEE,
    APPLICATION_DESACTIVEE,

    // --- Notifications (§12.9) : expéditions et préférence ------------------
    NOTIFICATION_ENVOYEE,
    NOTIFICATION_ECHEC,
    PREFERENCE_NOTIFICATION_MODIFIEE,

    // --- Journal d'audit lui-même (§7.4.3 : la consultation est auditée) -----
    AUDIT_CONSULTE,
    AUDIT_EXPORTE,
    AUDIT_VERIFIE,
    AUDIT_PARTITION_CREEE;

    /** Code tel qu'enregistré dans {@code journal_audit.action}. */
    public String code() {
        return name();
    }

    /** Le code appartient-il au catalogue ? */
    public static boolean connu(String code) {
        for (ActionAudit a : values()) {
            if (a.name().equals(code)) return true;
        }
        return false;
    }
}
