package com.ipt.ged.autorisation.evenement;

import com.ipt.ged.audit.EvenementAudit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Modification de l'accès à un document, hors habilitations : rattachement à
 * un espace (§12.4, {@code RATTACHEMENT_AJOUTE} / {@code RATTACHEMENT_RETIRE}),
 * niveau de confidentialité et personnes désignées (§12.3, auditées avant /
 * après). Publiée dans la transaction de l'écriture, pour le journal d'audit
 * de dev2.
 *
 * @param type       RATTACHEMENT_AJOUTE, RATTACHEMENT_RETIRE, CONFIDENTIALITE_MODIFIEE,
 *                   DESIGNATION_AJOUTEE, DESIGNATION_RETIREE
 * @param cibleId    nœud du rattachement ou identité désignée ; {@code null} pour le niveau
 * @param niveauAvant niveau de confidentialité avant, sinon {@code null}
 * @param niveauApres niveau de confidentialité après, sinon {@code null}
 */
public record AccesDocumentModifie(String type, UUID documentId, UUID cibleId, String niveauAvant, String niveauApres,
                                   UUID auteurId, Instant instant) implements EvenementAudit {

    public static final String RATTACHEMENT_AJOUTE = "RATTACHEMENT_AJOUTE";
    public static final String RATTACHEMENT_RETIRE = "RATTACHEMENT_RETIRE";
    public static final String CONFIDENTIALITE_MODIFIEE = "CONFIDENTIALITE_MODIFIEE";
    public static final String DESIGNATION_AJOUTEE = "DESIGNATION_AJOUTEE";
    public static final String DESIGNATION_RETIREE = "DESIGNATION_RETIREE";

    /** RATTACHEMENT_AJOUTE et RATTACHEMENT_RETIRE sont au catalogue ; les autres codes sont ceux du lot. */
    @Override
    public String action() { return type; }

    @Override
    public String objetType() { return "DOCUMENT"; }

    @Override
    public UUID objetId() { return documentId; }

    @Override
    public Map<String, Object> avant() {
        if (niveauAvant != null) return Map.of("confidentialite", niveauAvant);
        return RATTACHEMENT_RETIRE.equals(type) ? Map.of("noeudId", cibleId)
                : DESIGNATION_RETIREE.equals(type) ? Map.of("utilisateurId", cibleId) : null;
    }

    @Override
    public Map<String, Object> apres() {
        if (niveauApres != null) return Map.of("confidentialite", niveauApres);
        return RATTACHEMENT_AJOUTE.equals(type) ? Map.of("noeudId", cibleId)
                : DESIGNATION_AJOUTEE.equals(type) ? Map.of("utilisateurId", cibleId) : null;
    }

    @Override
    public UUID acteurUtilisateurId() { return auteurId; }
}
