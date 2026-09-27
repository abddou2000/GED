package com.ipt.ged.identite;

import com.ipt.ged.identite.annuaire.Annuaire;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.identite.erreur.IdentifiantsRefusesException;
import com.ipt.ged.identite.erreur.RenouvellementRefuseException;
import com.ipt.ged.identite.erreur.TropDeTentativesException;
import com.ipt.ged.identite.evenement.ConnexionEchouee;
import com.ipt.ged.identite.evenement.ConnexionReussie;
import com.ipt.ged.identite.evenement.MotifEchecConnexion;
import com.ipt.ged.identite.session.ServiceSessions;
import com.ipt.ged.security.ServiceJeton;
import com.ipt.ged.security.UtilisateurConnecte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Enchaînement de la connexion et du renouvellement (dossier technique §3.3,
 * §3.4.1).
 *
 * <p>Connexion : limitation de débit → recherche puis liaison dans l'annuaire →
 * provisionnement (ou mise à jour) de l'identité → ouverture de session → jeton
 * d'accès RS256. Chaque issue publie un événement ({@link ConnexionReussie},
 * {@link ConnexionEchouee}) que le journal d'audit écoute.
 *
 * <p>La liaison à l'annuaire se fait hors de toute transaction de base : une
 * attente de plusieurs secondes sur un contrôleur ne retient aucune connexion
 * PostgreSQL.
 */
@Service
public class ServiceConnexion {

    private static final Logger log = LoggerFactory.getLogger(ServiceConnexion.class);

    /** Résultat d'une connexion ou d'un renouvellement réussis. */
    public record Ouverture(String jetonAcces, long expireDansSecondes,
                            ServiceSessions.JetonRenouvellement renouvellement, UtilisateurConnecte utilisateur) {}

    private final LimiteurConnexions limiteur;
    private final Annuaire annuaire;
    private final ServiceIdentites identites;
    private final ServiceSessions sessions;
    private final ServiceJeton jetons;
    private final ApplicationEventPublisher evenements;

    public ServiceConnexion(LimiteurConnexions limiteur, Annuaire annuaire, ServiceIdentites identites,
                            ServiceSessions sessions, ServiceJeton jetons, ApplicationEventPublisher evenements) {
        this.limiteur = limiteur;
        this.annuaire = annuaire;
        this.identites = identites;
        this.sessions = sessions;
        this.jetons = jetons;
        this.evenements = evenements;
    }

    public Ouverture connecter(String identifiant, String motDePasse, String adresseIp, String agent) {
        String saisi = identifiant.trim();
        try {
            limiteur.consommer(adresseIp, saisi);
        } catch (TropDeTentativesException e) {
            echec(saisi, adresseIp, MotifEchecConnexion.TROP_DE_TENTATIVES);
            throw e;
        }

        FicheAnnuaire fiche;
        try {
            fiche = annuaire.authentifier(saisi, motDePasse);
        } catch (IdentifiantsRefusesException e) {
            echec(saisi, adresseIp, MotifEchecConnexion.IDENTIFIANTS_REFUSES);
            throw e;
        } catch (AnnuaireIndisponibleException e) {
            echec(saisi, adresseIp, MotifEchecConnexion.ANNUAIRE_INDISPONIBLE);
            throw e;
        }

        ServiceIdentites.Provisionnement p = identites.provisionner(fiche, true);
        ServiceSessions.JetonRenouvellement r = sessions.ouvrir(p.utilisateur().getId(), adresseIp, agent);
        UtilisateurConnecte principal = identites.principal(p.utilisateur().getId(), r.familleId()).orElseThrow();
        evenements.publishEvent(new ConnexionReussie(principal.getUtilisateurId(), principal.getUsername(),
                adresseIp, p.nouvelle(), Instant.now()));
        return ouverture(principal, r);
    }

    /**
     * Renouvellement silencieux : nouveau jeton d'accès et nouveau jeton de
     * renouvellement. Ne repasse pas par l'annuaire (décision D1).
     */
    public Ouverture renouveler(String jetonRenouvellement, String adresseIp, String agent) {
        ServiceSessions.JetonRenouvellement r = sessions.renouveler(jetonRenouvellement, adresseIp, agent);
        UtilisateurConnecte principal = identites.principal(r.utilisateurId(), r.familleId())
                .orElseThrow(RenouvellementRefuseException::new);
        return ouverture(principal, r);
    }

    private Ouverture ouverture(UtilisateurConnecte principal, ServiceSessions.JetonRenouvellement r) {
        String acces = jetons.emettre(principal.getUtilisateurId(), principal.getUsername(), r.familleId());
        return new Ouverture(acces, jetons.validiteSecondes(), r, principal);
    }

    private void echec(String identifiant, String adresseIp, MotifEchecConnexion motif) {
        // L'identifiant saisi est borné : il vient de l'appelant et finit dans les journaux.
        String borne = identifiant.length() > 64 ? identifiant.substring(0, 64) : identifiant;
        log.info("Connexion refusée ({}) pour « {} » depuis {}", motif, borne, adresseIp);
        evenements.publishEvent(new ConnexionEchouee(borne, adresseIp, motif, Instant.now()));
    }
}
