package com.ipt.ged.document.evenement;

import com.ipt.ged.common.ActeurCourant;

import java.util.UUID;

/**
 * Auteur d'une opération, sous les deux identités que l'audit doit distinguer
 * (§5.5) : l'employé (utilisateur, ou utilisateur délégué par une application)
 * et l'application appelante (clé API, lot intégration). Les deux sont
 * {@code null} pour un traitement technique (worker OCR, reprise).
 */
public record Acteur(UUID employeId, UUID applicationId) {

    public static final Acteur SYSTEME = new Acteur(null, null);

    /** Acteur de la requête en cours, lu dans le contexte de sécurité (jamais dans la requête). */
    public static Acteur courant() {
        return new Acteur(ActeurCourant.employeId(), null);
    }

    public boolean systeme() {
        return employeId == null && applicationId == null;
    }
}
