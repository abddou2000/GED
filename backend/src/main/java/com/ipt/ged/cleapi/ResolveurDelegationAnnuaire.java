package com.ipt.ged.cleapi;

import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.common.erreur.RegleMetierException;
import com.ipt.ged.common.erreur.ServiceIndisponibleException;
import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.identite.annuaire.Annuaire;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.security.UtilisateurConnecte;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Résolution de {@code X-On-Behalf-Of} (DAT §5.5) sur le lot identité (E2).
 *
 * <ol>
 *   <li>La clé doit porter l'attribut « délégation » (contrôlé par le filtre) et
 *       son application une liste d'adresses sources : une clé qui délègue ne
 *       s'utilise que depuis les serveurs déclarés.</li>
 *   <li>L'identifiant ({@code sAMAccountName}, ou objectGUID) est cherché parmi
 *       les identités GED, puis dans l'annuaire : une identité de l'annuaire
 *       jamais connectée est provisionnée sans rôle (cache d'annuaire compris).
 *       Inconnue des deux : 422 {@code IDENTITE_DELEGUEE_INVALIDE}.</li>
 *   <li>Option {@code ged.api.delegation.verifier-compte-annuaire} : l'annuaire est
 *       relu et une identité qui n'y figure plus est refusée (QR9, voir
 *       {@link ProprietesDelegation}).</li>
 * </ol>
 * Annuaire injoignable quand il faut le lire : 503, jamais d'exécution au nom
 * d'une identité non vérifiée.
 */
public class ResolveurDelegationAnnuaire implements ResolveurIdentiteDeleguee {

    private static final Pattern IDENTIFIANT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final UtilisateurRepository utilisateurs;
    private final ServiceIdentites identites;
    private final Annuaire annuaire;
    private final ApplicationRepository applications;
    private final ProprietesDelegation proprietes;

    public ResolveurDelegationAnnuaire(UtilisateurRepository utilisateurs, ServiceIdentites identites,
                                       Annuaire annuaire, ApplicationRepository applications,
                                       ProprietesDelegation proprietes) {
        this.utilisateurs = utilisateurs;
        this.identites = identites;
        this.annuaire = annuaire;
        this.applications = applications;
        this.proprietes = proprietes;
    }

    @Override
    public IdentiteDeleguee resoudre(ApplicationAuthentifiee application, String valeur) {
        boolean adresses = applications.findById(application.applicationId())
                .map(a -> !a.adresses().isEmpty()).orElse(false);
        if (!adresses) {
            throw new AccesRefuseException(CodesErreurCleApi.DELEGATION_SANS_ADRESSES,
                    "Une clé qui délègue ne s'utilise que depuis les adresses déclarées de son application.");
        }
        String v = valeur == null ? "" : valeur.trim();
        UUID guid = guid(v);
        if (guid == null && !IDENTIFIANT.matcher(v).matches()) throw invalide();

        Optional<Utilisateur> connue = guid != null ? utilisateurs.findByObjectGuid(guid)
                : utilisateurs.findByIdentifiant(v);
        Utilisateur u;
        if (connue.isPresent()) {
            u = connue.get();
            if (proprietes.verifierCompteAnnuaire() && lire(null, u.getObjectGuid()).isEmpty()) throw invalide();
        } else {
            FicheAnnuaire fiche = lire(guid == null ? v : null, guid).orElseThrow(ResolveurDelegationAnnuaire::invalide);
            if (!proprietes.provisionnerInconnus()) throw invalide();
            u = identites.provisionner(fiche, false).utilisateur();
        }
        UtilisateurConnecte principal = identites.principal(u.getId(), null).orElseThrow(ResolveurDelegationAnnuaire::invalide);
        return new IdentiteDeleguee(u.getId(), u.getIdentifiant(), principal);
    }

    private Optional<FicheAnnuaire> lire(String identifiant, UUID guid) {
        try {
            return guid != null ? annuaire.rechercherParGuid(guid) : annuaire.rechercherParIdentifiant(identifiant);
        } catch (AnnuaireIndisponibleException e) {
            throw new ServiceIndisponibleException("ANNUAIRE_INDISPONIBLE",
                    "Annuaire injoignable : l'identité déléguée ne peut pas être vérifiée.", e);
        }
    }

    private static UUID guid(String v) {
        try {
            return v.length() == 36 ? UUID.fromString(v) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RegleMetierException invalide() {
        return new RegleMetierException(CodesErreurCleApi.IDENTITE_DELEGUEE_INVALIDE,
                "Identité déléguée inconnue de l'annuaire ou invalide.");
    }
}
