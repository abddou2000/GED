package com.ipt.ged.identite;

import com.ipt.ged.identite.annuaire.Annuaire;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache annuaire (dossier technique §3.4.2, décision T2) : nom, prénom, courriel
 * et direction d'une identité, écrits à chaque connexion, expirant après 15
 * minutes et régénérés à la demande par la clé {@code objectGUID}.
 *
 * <p>Aucun attribut d'état du compte n'y figure ni n'est relu (décision D1) : la
 * régénération ne sert qu'à l'affichage et aux notifications.
 */
@Service
public class ServiceCacheAnnuaire {

    private static final Logger log = LoggerFactory.getLogger(ServiceCacheAnnuaire.class);

    private final CacheAnnuaireRepository cache;
    private final UtilisateurRepository utilisateurs;
    private final Annuaire annuaire;
    private final ProprietesIdentite proprietes;

    public ServiceCacheAnnuaire(CacheAnnuaireRepository cache, UtilisateurRepository utilisateurs,
                                Annuaire annuaire, ProprietesIdentite proprietes) {
        this.cache = cache;
        this.utilisateurs = utilisateurs;
        this.annuaire = annuaire;
        this.proprietes = proprietes;
    }

    /** Remplace l'entrée d'une identité par une lecture fraîche de l'annuaire. */
    @Transactional
    public EntreeCacheAnnuaire enregistrer(UUID utilisateurId, FicheAnnuaire fiche) {
        Instant maintenant = Instant.now();
        EntreeCacheAnnuaire e = cache.findByUtilisateurId(utilisateurId).orElseGet(EntreeCacheAnnuaire::new);
        e.setUtilisateurId(utilisateurId);
        e.setIdentifiant(fiche.identifiant());
        e.setPrenom(fiche.prenom());
        e.setNom(fiche.nom());
        e.setNomAffiche(fiche.nomAffiche());
        e.setCourriel(fiche.courriel());
        e.setDirection(fiche.direction());
        e.setLuLe(maintenant);
        e.setExpireLe(maintenant.plus(proprietes.getCache().getDuree()));
        return cache.save(e);
    }

    /**
     * Entrée à jour d'une identité : relue dans l'annuaire si elle a expiré.
     * Annuaire injoignable ou entrée disparue de l'annuaire : la dernière
     * lecture est rendue telle quelle — l'affichage d'un nom n'a pas à échouer.
     */
    @Transactional
    public Optional<EntreeCacheAnnuaire> lire(UUID utilisateurId) {
        Optional<EntreeCacheAnnuaire> courante = cache.findByUtilisateurId(utilisateurId);
        if (courante.isPresent() && !courante.get().estExpiree(Instant.now())) {
            return courante;
        }
        Optional<Utilisateur> u = utilisateurs.findById(utilisateurId);
        if (u.isEmpty()) return courante;
        try {
            Optional<FicheAnnuaire> fiche = annuaire.rechercherParGuid(u.get().getObjectGuid());
            if (fiche.isPresent()) {
                return Optional.of(enregistrer(utilisateurId, fiche.get()));
            }
        } catch (AnnuaireIndisponibleException e) {
            log.debug("Cache annuaire non régénéré (annuaire injoignable) pour {}", utilisateurId);
        }
        return courante;
    }
}
