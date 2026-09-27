package com.ipt.ged.cleapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la délégation d'identité (DAT §5.5), sous {@code ged.api.delegation}.
 *
 * @param provisionnerInconnus  identité présente dans l'annuaire mais jamais connectée
 *                              à la GED : provisionnée sans rôle (vrai, défaut) ou refusée
 * @param verifierCompteAnnuaire relire l'annuaire à chaque délégation et refuser une
 *                              identité qui n'y figure plus. <b>Question QR9 (décision D1)
 *                              en attente</b> : le rejet d'un compte <i>désactivé</i>
 *                              suppose de lire l'état du compte, que la GED ne demande pas à
 *                              l'annuaire aujourd'hui (D1, D3). Faux par défaut : seule
 *                              l'existence est vérifiée.
 */
@ConfigurationProperties("ged.api.delegation")
public record ProprietesDelegation(Boolean provisionnerInconnus, Boolean verifierCompteAnnuaire) {

    public ProprietesDelegation {
        provisionnerInconnus = provisionnerInconnus == null || provisionnerInconnus;
        verifierCompteAnnuaire = verifierCompteAnnuaire != null && verifierCompteAnnuaire;
    }
}
