package com.ipt.ged.document.archivage;

import java.util.UUID;

/**
 * <b>Contrat pour le lot cycle de vie (dev3) : statut d'archivage d'un
 * document</b> (dossier technique §12.6).
 *
 * <p>Le document et ses colonnes ({@code statut_conservation}, {@code archive_le},
 * {@code archive_par}) appartiennent au lot modèle (dev1). Le lot cycle de vie
 * fait le reste — vérification de l'empreinte, copie PDF/A, événement d'audit —
 * puis appelle ces méthodes dans SA transaction.
 *
 * <p>Refus : 409 {@code DOCUMENT_VERROUILLE} si le document est verrouillé
 * (§12.8 : le verrou est contrôlé par l'archivage). Aucun contrôle de droit :
 * la permission Archiver est vérifiée par l'appelant.
 */
public interface ArchivageDocuments {

    /**
     * Passe le document en ARCHIVE (idempotent : un document déjà archivé garde
     * sa date et son archiviste d'origine).
     *
     * @return vrai si le document vient d'être archivé, faux s'il l'était déjà
     */
    boolean archiver(UUID documentId, UUID auteurUtilisateurId);

    /** Repasse le document en ACTIF ; faux s'il n'était pas archivé. */
    boolean desarchiver(UUID documentId);
}
