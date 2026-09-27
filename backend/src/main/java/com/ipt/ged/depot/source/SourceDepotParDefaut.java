package com.ipt.ged.depot.source;

import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Origine d'un dépôt sans le lot intégration : un utilisateur authentifié
 * dépose par l'interface ; une application (autorité {@code ROLE_APPLICATION}
 * du lot clés d'API) est reconnue comme canal {@code API}, sans identifiant
 * tant que le lot intégration ne fournit pas sa propre {@link SourceDepot}.
 */
public class SourceDepotParDefaut implements SourceDepot {

    static final String AUTORITE_APPLICATION = "ROLE_APPLICATION";

    @Override
    public Origine origine(Authentication a) {
        if (a != null && a.getPrincipal() instanceof UtilisateurConnecte u) {
            return Origine.interfaceWeb(u.getUtilisateurId());
        }
        if (a != null && a.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(AUTORITE_APPLICATION::equals)) {
            return new Origine(CanalDepot.API, null, a.getName(), null, false);
        }
        return new Origine(CanalDepot.INTERFACE, null, null, null, false);
    }
}
