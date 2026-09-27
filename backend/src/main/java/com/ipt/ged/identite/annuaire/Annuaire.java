package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.identite.erreur.IdentifiantsRefusesException;

import java.util.Optional;
import java.util.UUID;

/**
 * L'annuaire de l'entreprise, consulté pour deux usages seulement (dossier
 * technique §3.2) : vérifier une identité et lire les informations de
 * l'employé — jamais pour en dériver un droit d'accès.
 */
public interface Annuaire {

    /**
     * Recherche puis liaison (search-then-bind) : le compte de service trouve
     * l'entrée par son {@code sAMAccountName}, puis une liaison est tentée avec le
     * DN trouvé et le mot de passe saisi. Le mot de passe n'est ni stocké, ni
     * comparé, ni journalisé côté GED.
     *
     * @throws IdentifiantsRefusesException  identifiant inconnu, mot de passe faux
     *                                       ou compte désactivé (indistincts)
     * @throws AnnuaireIndisponibleException aucun contrôleur ne répond
     */
    FicheAnnuaire authentifier(String identifiant, String motDePasse);

    /** Relecture par la clé immuable, pour régénérer le cache annuaire. */
    Optional<FicheAnnuaire> rechercherParGuid(UUID objectGuid);

    /** Recherche par le compte de service, sans liaison de l'utilisateur. */
    Optional<FicheAnnuaire> rechercherParIdentifiant(String identifiant);

    /**
     * Ouvre puis ferme une connexion du compte de service : sert à la sonde de
     * santé.
     *
     * @throws AnnuaireIndisponibleException si aucun contrôleur ne répond
     */
    void sonder();
}
