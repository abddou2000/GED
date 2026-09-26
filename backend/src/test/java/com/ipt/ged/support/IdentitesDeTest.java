package com.ipt.ged.support;

import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.RoleRepository;
import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.identite.annuaire.Annuaire;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Fournisseur d'identités des tests, derrière {@code @WithUserDetails}.
 *
 * <p>N'existe que sur le classpath de test : l'application, elle, n'a aucun
 * {@code UserDetailsService} (l'authentification passe par l'annuaire).
 *
 * <p>Il emprunte le vrai chemin de l'application : la personne est cherchée
 * dans le simulateur d'annuaire (compte de service), puis provisionnée par
 * {@link ServiceIdentites} — rattachement à la fiche employé, rôle
 * Administrateur d'amorçage pour {@code sbennani}. Seule entorse assumée : les
 * acteurs secondaires reçoivent le rôle Utilisateur standard, qu'un
 * Administrateur leur donnerait par l'interface (lot E3), sans quoi ils
 * n'auraient accès à rien.
 */
@Component
public class IdentitesDeTest implements UserDetailsService {

    /** Rôle donné à la création pour les acteurs secondaires des tests. */
    private static final Map<String, String> ROLES_DE_TEST = Map.of(
            Comptes.SECOND_ACTEUR, Role.UTILISATEUR_STANDARD,
            Comptes.TROISIEME_ACTEUR, Role.UTILISATEUR_STANDARD);

    private final ServiceIdentites identites;
    private final Annuaire annuaire;
    private final UtilisateurRepository utilisateurs;
    private final RoleRepository roles;

    public IdentitesDeTest(ServiceIdentites identites, Annuaire annuaire, UtilisateurRepository utilisateurs,
                           RoleRepository roles) {
        this.identites = identites;
        this.annuaire = annuaire;
        this.utilisateurs = utilisateurs;
        this.roles = roles;
    }

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String identifiant) {
        return identites.principalParIdentifiant(identifiant).map(UserDetails.class::cast)
                .orElseGet(() -> provisionner(identifiant));
    }

    private UserDetails provisionner(String identifiant) {
        FicheAnnuaire fiche = annuaire.rechercherParIdentifiant(identifiant)
                .orElseThrow(() -> new UsernameNotFoundException("Absent du simulateur d'annuaire : " + identifiant));
        var u = identites.provisionner(fiche, false).utilisateur();
        String role = ROLES_DE_TEST.get(identifiant);
        if (role != null) {
            roles.findByCode(role).ifPresent(r -> u.getRoles().add(r));
            utilisateurs.save(u);
        }
        return identites.principalParIdentifiant(identifiant).orElseThrow();
    }
}
