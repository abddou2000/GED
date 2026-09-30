package com.ipt.ged.cleapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Réglages de la délégation d'identité (DAT §5.5), sous {@code ged.api.delegation}.
 *
 * @param provisionnerInconnus  identité présente dans l'annuaire mais jamais connectée
 *                              à la GED : provisionnée sans rôle (vrai, défaut) ou refusée
 * @param verifierCompteAnnuaire <b>sans effet depuis la décision D15</b> : l'état du compte
 *                              ({@code userAccountControl}) est désormais lu à chaque
 *                              délégation, ce qui vérifie aussi que le compte existe
 *                              toujours. Conservé pour ne pas invalider une configuration
 *                              existante.
 * @param cacheEtatCompte       durée pendant laquelle l'état lu d'un compte est réutilisé
 *                              (D15 : « cache court de quelques minutes au plus ») ; 2 min
 *                              par défaut, 5 min au plus, 0 = relecture à chaque appel
 */
@ConfigurationProperties("ged.api.delegation")
public record ProprietesDelegation(Boolean provisionnerInconnus, Boolean verifierCompteAnnuaire,
                                   Duration cacheEtatCompte) {

    public ProprietesDelegation {
        provisionnerInconnus = provisionnerInconnus == null || provisionnerInconnus;
        verifierCompteAnnuaire = verifierCompteAnnuaire != null && verifierCompteAnnuaire;
        cacheEtatCompte = cacheEtatCompte == null ? Duration.ofMinutes(2) : cacheEtatCompte;
    }
}
