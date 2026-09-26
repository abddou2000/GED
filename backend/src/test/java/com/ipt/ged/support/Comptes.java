package com.ipt.ged.support;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;

import java.util.UUID;

/**
 * Identités utilisées par les tests, désignées par l'identifiant d'annuaire
 * ({@code sAMAccountName}) que {@code @WithUserDetails} passe au
 * {@code UserDetailsService} de test ({@link IdentitesDeTest}).
 *
 * <h2>Pourquoi {@code @WithUserDetails} et pas {@code @WithMockUser}</h2>
 * <p>{@code @WithMockUser} fabrique un principal d'un autre type : les
 * contrôleurs qui injectent {@code @AuthenticationPrincipal UtilisateurConnecte}
 * recevraient {@code null}. {@code @WithUserDetails} passe par l'annuaire simulé
 * et le vrai provisionnement : le principal est un {@code UtilisateurConnecte}
 * complet, rôles et {@code employeId} lus en base.
 *
 * <h2>Ce que contient l'environnement de test</h2>
 * <ul>
 *   <li>{@code EmployeSeeder} sème quatre employés (Sara, Karim, Yasmine, Omar) ;</li>
 *   <li>le simulateur d'annuaire ({@code annuaire/annuaire-test.ldif}) connaît
 *       {@code sbennani}, {@code kelfassi}, {@code yalaoui}, {@code nidrissi}
 *       (aucune fiche employé : une fiche est créée) et {@code otazi}
 *       (désactivé), tous au mot de passe {@link #MOT_DE_PASSE} ;</li>
 *   <li>{@link #ADMIN} reçoit le rôle Administrateur par amorçage ;
 *       {@link #SECOND_ACTEUR} et {@link #TROISIEME_ACTEUR} le rôle Utilisateur
 *       standard ; {@link #SANS_ROLE} aucun.</li>
 * </ul>
 *
 * <p>Les identifiants techniques sont des UUID tirés à l'insertion : les
 * employés se désignent par leur nom ({@link #idAdmin}…), jamais par une valeur
 * figée. Signatures publiques stables : les tests des autres lots en dépendent.
 */
public final class Comptes {

    private Comptes() {}

    /** L'Administrateur (Sara Bennani). */
    public static final String ADMIN = "sbennani";

    /** Second acteur authentifié (Karim El Fassi), rôle Utilisateur standard. */
    public static final String SECOND_ACTEUR = "kelfassi";

    /** Troisième acteur (Yasmine Alaoui), rôle Utilisateur standard. */
    public static final String TROISIEME_ACTEUR = "yalaoui";

    /** Personne de l'annuaire sans fiche employé ni rôle : page d'accueil vide. */
    public static final String SANS_ROLE = "nidrissi";

    /** Compte désactivé dans l'annuaire (userAccountControl 514). */
    public static final String DESACTIVE = "otazi";

    /** Mot de passe de tous les comptes du simulateur de test (jetable). */
    public static final String MOT_DE_PASSE = "test-only-password";

    /** Employé titulaire de l'identité administrateur (Sara Bennani). */
    public static UUID idAdmin(EmployeRepository employes) {
        return idEmploye(employes, "Sara", "Bennani");
    }

    /** Employé du second acteur (Karim El Fassi). */
    public static UUID idSecondActeur(EmployeRepository employes) {
        return idEmploye(employes, "Karim", "El Fassi");
    }

    /** Troisième employé du jeu d'essai (Yasmine Alaoui), approbateur d'étape. */
    public static UUID idTroisiemeEmploye(EmployeRepository employes) {
        return idEmploye(employes, "Yasmine", "Alaoui");
    }

    private static UUID idEmploye(EmployeRepository employes, String prenom, String nom) {
        return employes.findAll().stream()
                .filter(e -> prenom.equals(e.getFirstName()) && nom.equals(e.getLastName()))
                .map(Employe::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Employé du jeu d'essai introuvable : " + prenom + " " + nom));
    }
}
