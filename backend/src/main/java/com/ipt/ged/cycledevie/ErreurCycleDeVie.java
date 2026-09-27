package com.ipt.ged.cycledevie;

import com.ipt.ged.fichier.ErreurFichierException;
import org.springframework.http.HttpStatus;

/**
 * Refus du cycle de vie des documents (purge, archivage, export), avec code
 * stable (DAT 5.3.2 : 403 permission, 404 hors périmètre, 409 état
 * incompatible).
 *
 * <p>Hérite d'{@link ErreurFichierException}, donc d'{@code ExceptionMetier} :
 * rendue par le gestionnaire commun avec son statut et son code, sans
 * gestionnaire propre au lot.
 */
public class ErreurCycleDeVie extends ErreurFichierException {

    /** 409 — la purge ne s'applique qu'à un document déjà en corbeille (§12.5). */
    public static final String DOCUMENT_NON_SUPPRIME = "DOCUMENT_NON_SUPPRIME";
    /** 409 — document archivé : lecture seule totale, seul le désarchivage y fait exception (§12.6). */
    public static final String DOCUMENT_ARCHIVE = "DOCUMENT_ARCHIVE";
    /** 409 — désarchivage d'un document qui n'est pas archivé. */
    public static final String DOCUMENT_NON_ARCHIVE = "DOCUMENT_NON_ARCHIVE";
    /** 409 — version courante encore dans l'ancien stockage en clair (reprise à terminer). */
    public static final String FICHIER_NON_REPRIS = "FICHIER_NON_REPRIS";
    /** 409 — le document a changé pendant la préparation (nouvelle version, corbeille) : à relancer. */
    public static final String DOCUMENT_MODIFIE = "DOCUMENT_MODIFIE";
    /** 409 — document verrouillé : le verrou gèle aussi l'archivage (§12.8). */
    public static final String DOCUMENT_VERROUILLE = "DOCUMENT_VERROUILLE";
    /** 409 — document en corbeille : ni archivage ni export. */
    public static final String DOCUMENT_EN_CORBEILLE = "DOCUMENT_EN_CORBEILLE";
    /** 409 — dossier archivé : aucun dépôt (revue client, question Q7). */
    public static final String DOSSIER_ARCHIVE = "DOSSIER_ARCHIVE";
    /** 409 — un job d'archivage est déjà en cours sur ce dossier. */
    public static final String ARCHIVAGE_EN_COURS = "ARCHIVAGE_EN_COURS";
    /** 409 — le job n'est plus annulable (terminé ou annulé). */
    public static final String JOB_TERMINE = "JOB_TERMINE";
    /** 403 — permission Purger, Archiver ou Désarchiver absente. */
    public static final String PERMISSION_REFUSEE = "PERMISSION_REFUSEE";
    /** 404 — objet inexistant ou hors du périmètre de l'appelant (jamais distingués, P5). */
    public static final String INTROUVABLE = "INTROUVABLE";
    /** 409 — l'export n'est pas (ou plus) téléchargeable. */
    public static final String EXPORT_INDISPONIBLE = "EXPORT_INDISPONIBLE";

    public ErreurCycleDeVie(HttpStatus statut, String code, String message) {
        super(statut, code, message);
    }

    static ErreurCycleDeVie conflit(String code, String message) {
        return new ErreurCycleDeVie(HttpStatus.CONFLICT, code, message);
    }

    static ErreurCycleDeVie permission(String operation) {
        return new ErreurCycleDeVie(HttpStatus.FORBIDDEN, PERMISSION_REFUSEE,
                "Permission « " + operation + " » requise.");
    }

    static ErreurCycleDeVie introuvable(String quoi) {
        return new ErreurCycleDeVie(HttpStatus.NOT_FOUND, INTROUVABLE, quoi + " introuvable.");
    }
}
