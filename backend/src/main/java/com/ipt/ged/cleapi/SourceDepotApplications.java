package com.ipt.ged.cleapi;

import com.ipt.ged.depot.source.CanalDepot;
import com.ipt.ged.depot.source.SourceDepot;
import com.ipt.ged.depot.source.SourceDepotParDefaut;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Origine d'un dépôt fait par une application (T-040, §5.1, §5.5) : canal
 * {@code API} (le bureau d'ordre est reconnu ensuite à son code par
 * {@code ResolutionOrigineDepot}), application appelante et, sous délégation
 * {@code X-On-Behalf-Of}, la personne pour le compte de laquelle elle dépose.
 *
 * <p>Le test porte sur le type du jeton et non sur {@code getPrincipal()} :
 * sous délégation, le principal est l'utilisateur délégué (auteur de
 * l'écriture), et un test sur le principal classerait le dépôt parmi ceux de
 * l'interface en perdant l'application (ANO-E9-001). Hors application,
 * l'implémentation par défaut (utilisateur de l'interface) s'applique.
 */
@Component
@Primary
public class SourceDepotApplications implements SourceDepot {

    private final SourceDepot interfaceWeb = new SourceDepotParDefaut();

    @Override
    public Origine origine(Authentication authentification) {
        if (authentification instanceof ApplicationAuthentifiee a) {
            boolean delegue = a.deleguee() != null;
            return new Origine(CanalDepot.API, a.applicationId(), a.getName(),
                    delegue ? a.deleguee().getUtilisateurId() : null, delegue);
        }
        return interfaceWeb.origine(authentification);
    }
}
