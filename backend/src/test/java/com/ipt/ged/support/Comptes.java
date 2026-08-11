package com.ipt.ged.support;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.security.CompteUtilisateur;
import com.ipt.ged.security.CompteUtilisateurRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Comptes utilisés par les tests, désignés par l'e-mail que
 * {@code @WithUserDetails} passe au {@code UserDetailsService}.
 *
 * <h2>Pourquoi {@code @WithUserDetails} et pas {@code @WithMockUser}</h2>
 * <p>{@code @WithMockUser} fabrique un principal de type
 * {@code org.springframework.security.core.userdetails.User}. Or les
 * contrôleurs de signature et d'employé injectent
 * {@code @AuthenticationPrincipal UtilisateurConnecte} : avec un principal
 * d'un autre type, Spring injecte {@code null} et les tests échoueraient sur
 * un {@code NullPointerException} au lieu de vérifier quoi que ce soit — ou,
 * pire, on serait tenté d'assouplir le contrôleur pour « faire passer ».
 *
 * <p>{@code @WithUserDetails} passe par le vrai {@code ServiceUtilisateurs},
 * donc par la vraie base : le principal est un {@code UtilisateurConnecte}
 * complet, avec l'{@code employeId} réellement lu en base. Les tests exercent
 * ainsi la même chaîne d'authentification que la production.
 *
 * <h2>Ce que la base de test contient</h2>
 * <p>{@code EmployeSeeder} sème quatre employés ; {@code CompteSeeder} n'amorce
 * qu'un <b>seul</b> compte de connexion — l'administrateur — dont l'adresse est
 * fixée par {@code ged.securite.email-admin} dans {@code application-test.yml},
 * soit celle de l'employé 1.
 *
 * <p>Les tests qui ont besoin d'un <b>second acteur authentifié</b> (le circuit
 * de signature oppose l'assigné d'une étape à quelqu'un d'autre) le créent
 * eux-mêmes via {@link #ouvrirCompte}. C'est de l'échafaudage de test assumé :
 * la propriété vérifiée — « seul l'assigné signe, et l'identité vient du
 * jeton » — porte sur des employés distincts, elle ne se démontre pas avec un
 * seul principal.
 */
public final class Comptes {

    private Comptes() {}

    /** Le compte unique amorcé par {@code CompteSeeder}, rattaché à l'employé 1. */
    public static final String ADMIN = "sara.bennani@marchica.ma";

    /** Second acteur, ouvert à la demande par {@link #ouvrirCompte}. */
    public static final String SECOND_ACTEUR = "karim.elfassi@marchica.ma";

    public static final long ID_ADMIN = 1L;
    public static final long ID_SECOND_ACTEUR = 2L;

    /**
     * Ouvre un compte de connexion pour un employé donné, s'il n'en a pas déjà
     * un. Réservé aux tests qui doivent opposer deux acteurs authentifiés.
     */
    public static CompteUtilisateur ouvrirCompte(CompteUtilisateurRepository comptes,
                                                 EmployeRepository employes,
                                                 PasswordEncoder encodeur,
                                                 long employeId,
                                                 String email,
                                                 String motDePasse) {
        return comptes.findByEmailIgnoreCase(email).orElseGet(() -> {
            Employe e = employes.findById(employeId).orElseThrow();
            return comptes.save(new CompteUtilisateur(email, encodeur.encode(motDePasse), e));
        });
    }
}
