package com.ipt.ged.cleapi;

import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.common.erreur.RegleMetierException;
import com.ipt.ged.common.erreur.ServiceIndisponibleException;
import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.identite.annuaire.Annuaire;
import com.ipt.ged.identite.annuaire.EtatCompteAnnuaire;
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
 *   <li><b>Compte actif</b> (décision D15, risque R28) : l'état du compte
 *       ({@code userAccountControl}) est lu dans l'annuaire, avec un cache court
 *       ({@link com.ipt.ged.identite.annuaire.EtatCompteEnCache}). Compte
 *       désactivé, disparu ou d'état illisible : 422
 *       {@code IDENTITE_DELEGUEE_INVALIDE}, motif précis au journal d'audit, et
 *       aucun provisionnement. Exception bornée à D1 : la connexion
 *       interactive ne lit toujours pas cet état.</li>
 * </ol>
 * Annuaire injoignable quand il faut le lire : 503, jamais d'exécution au nom
 * d'une identité non vérifiée.
 */
public class ResolveurDelegationAnnuaire implements ResolveurIdentiteDeleguee {

    private static final Pattern IDENTIFIANT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final UtilisateurRepository utilisateurs;
    private final ServiceIdentites identites;
    private final Annuaire annuaire;
    private final EtatCompteAnnuaire etatCompte;
    private final ApplicationRepository applications;
    private final ProprietesDelegation proprietes;

    public ResolveurDelegationAnnuaire(UtilisateurRepository utilisateurs, ServiceIdentites identites,
                                       Annuaire annuaire, EtatCompteAnnuaire etatCompte,
                                       ApplicationRepository applications, ProprietesDelegation proprietes) {
        this.utilisateurs = utilisateurs;
        this.identites = identites;
        this.annuaire = annuaire;
        this.etatCompte = etatCompte;
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
        if (guid == null && !IDENTIFIANT.matcher(v).matches()) throw invalide(null);

        Optional<Utilisateur> connue = guid != null ? utilisateurs.findByObjectGuid(guid)
                : utilisateurs.findByIdentifiant(v);
        Utilisateur u;
        if (connue.isPresent()) {
            u = connue.get();
            exigerCompteActif(u.getObjectGuid(), u.getIdentifiant());
        } else {
            FicheAnnuaire fiche = lire(guid == null ? v : null, guid)
                    .orElseThrow(() -> invalide("identité inconnue de la GED et de l'annuaire"));
            if (!proprietes.provisionnerInconnus()) throw invalide("identité jamais connectée, provisionnement désactivé");
            // Avant le provisionnement : un compte désactivé ne devient pas une identité GED.
            exigerCompteActif(fiche.objectGuid(), fiche.identifiant());
            u = identites.provisionner(fiche, false).utilisateur();
        }
        UtilisateurConnecte principal = identites.principal(u.getId(), null)
                .orElseThrow(() -> invalide("identité GED introuvable"));
        return new IdentiteDeleguee(u.getId(), u.getIdentifiant(), principal);
    }

    /** D15 : aucune délégation vers un compte désactivé, disparu ou d'état non vérifiable. */
    private void exigerCompteActif(UUID objectGuid, String identifiant) {
        EtatCompteAnnuaire.Etat etat;
        try {
            etat = etatCompte.etat(objectGuid);
        } catch (AnnuaireIndisponibleException e) {
            throw new ServiceIndisponibleException("ANNUAIRE_INDISPONIBLE",
                    "Annuaire injoignable : l'identité déléguée ne peut pas être vérifiée.", e);
        }
        switch (etat) {
            case ACTIF -> { }
            case DESACTIVE -> throw invalide("compte désactivé dans l'annuaire (" + identifiant + ")");
            case INTROUVABLE -> throw invalide("compte absent de l'annuaire (" + identifiant + ")");
            case INDETERMINE -> throw invalide("état du compte illisible dans l'annuaire (" + identifiant + ")");
        }
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

    /**
     * Refus 422, même libellé pour l'application quelle qu'en soit la cause (elle
     * n'apprend pas l'état d'un compte) ; la cause précise va au journal d'audit.
     */
    private static RegleMetierException invalide(String motif) {
        RegleMetierException e = new RegleMetierException(CodesErreurCleApi.IDENTITE_DELEGUEE_INVALIDE,
                "Identité déléguée inconnue de l'annuaire ou invalide.");
        if (motif != null) e.avec(MOTIF_AUDIT, motif);
        return e;
    }

    /** Propriété de l'exception lue par {@link FiltreCleApi} pour le journal ; jamais renvoyée au client. */
    static final String MOTIF_AUDIT = "motifAudit";
}
