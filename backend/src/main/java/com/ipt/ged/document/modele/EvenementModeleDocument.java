package com.ipt.ged.document.modele;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.audit.ResultatAudit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Événement d'audit du lot modèle sur un document (§12.5, §12.7, §12.8) :
 * versement, désignation de la version courante, pose et levée du verrou,
 * déplacement, renommage, modification des métadonnées, re-typologisation.
 * Publié dans la transaction de l'écriture ; le journal (dev2) l'enregistre
 * avec les valeurs avant et après.
 *
 * @param action code du catalogue d'audit (VERSION_AJOUTEE, VERSION_RESTAUREE,
 *               DOCUMENT_VERROUILLE, DOCUMENT_DEVERROUILLE, DOCUMENT_DEPLACE,
 *               DOCUMENT_RENOMME, METADONNEES_MODIFIEES) ou du lot (DOCUMENT_RETYPE)
 */
public record EvenementModeleDocument(String action, UUID documentId, Map<String, Object> avant,
                                      Map<String, Object> apres, String motif, UUID auteurId,
                                      ResultatAudit resultat, Instant instant) implements EvenementAudit {

    public static final String VERSION_AJOUTEE = "VERSION_AJOUTEE";
    public static final String VERSION_RESTAUREE = "VERSION_RESTAUREE";
    public static final String DOCUMENT_VERROUILLE = "DOCUMENT_VERROUILLE";
    public static final String DOCUMENT_DEVERROUILLE = "DOCUMENT_DEVERROUILLE";
    public static final String DOCUMENT_DEPLACE = "DOCUMENT_DEPLACE";
    public static final String DOCUMENT_RENOMME = "DOCUMENT_RENOMME";
    public static final String METADONNEES_MODIFIEES = "METADONNEES_MODIFIEES";
    public static final String DOCUMENT_RETYPE = "DOCUMENT_RETYPE";

    public static EvenementModeleDocument succes(String action, UUID documentId, Map<String, Object> avant,
                                                 Map<String, Object> apres, String motif, UUID auteurId) {
        return new EvenementModeleDocument(action, documentId, avant, apres, motif, auteurId, ResultatAudit.SUCCES,
                Instant.now());
    }

    @Override
    public String objetType() { return "DOCUMENT"; }

    @Override
    public UUID objetId() { return documentId; }

    @Override
    public UUID acteurUtilisateurId() { return auteurId; }
}
