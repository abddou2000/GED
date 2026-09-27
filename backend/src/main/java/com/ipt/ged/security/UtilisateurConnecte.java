package com.ipt.ged.security;

import com.ipt.ged.identite.Utilisateur;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * L'appelant authentifié, tel que la chaîne de sécurité le manipule.
 *
 * <p>Instantané immuable, reconstruit à chaque requête depuis la base : les rôles
 * GED sont relus à chaque appel (dossier technique §3.3), jamais tirés du jeton.
 *
 * <p>Depuis le lot E3, un rôle est une habilitation (§12.2.1) :
 * <ul>
 *   <li>{@link #getRoles()} : tous les rôles détenus, sur un nœud ou en portée
 *       globale, directement ou par un groupe GED — un compte qui n'en a aucun
 *       voit la page d'accueil vide ;</li>
 *   <li>autorités {@code ROLE_<code>} : les seuls rôles de PORTÉE GLOBALE. Un
 *       Administrateur habilité sur un seul espace n'ouvre donc pas
 *       {@code /api/v1/admin/**}.</li>
 * </ul>
 * Les permissions fines, elles, sont décidées par {@code AccessPredicate}.
 *
 * <p><b>Aucun mot de passe</b> : {@link #getPassword()} renvoie {@code null}, la
 * vérification étant déléguée à l'annuaire.
 */
public class UtilisateurConnecte implements UserDetails {

    private final UUID utilisateurId;
    private final String identifiant;
    private final UUID employeId;
    private final String nomComplet;
    private final Set<String> rolesGlobaux;
    private final Set<String> roles;
    private final UUID sessionId;

    public UtilisateurConnecte(UUID utilisateurId, String identifiant, UUID employeId, String nomComplet,
                               Set<String> rolesGlobaux, Set<String> roles, UUID sessionId) {
        this.utilisateurId = utilisateurId;
        this.identifiant = identifiant;
        this.employeId = employeId;
        this.nomComplet = nomComplet;
        this.rolesGlobaux = Set.copyOf(rolesGlobaux);
        this.roles = Set.copyOf(roles);
        this.sessionId = sessionId;
    }

    /** Instantané d'une identité chargée, avec les rôles résolus de ses habilitations. */
    public static UtilisateurConnecte depuis(Utilisateur u, Set<String> rolesGlobaux, Set<String> roles,
                                             UUID sessionId) {
        return new UtilisateurConnecte(u.getId(), u.getIdentifiant(), u.getEmploye().getId(),
                u.getEmploye().getFullName(), rolesGlobaux, roles, sessionId);
    }

    public UUID getUtilisateurId() { return utilisateurId; }

    /** La personne métier : c'est elle qui dépose, possède un dossier, signe. */
    public UUID getEmployeId() { return employeId; }

    public String getNomComplet() { return nomComplet; }

    /** Tous les rôles détenus (toute portée). */
    public Set<String> getRoles() { return roles; }

    /** Rôles de portée globale. */
    public Set<String> getRolesGlobaux() { return rolesGlobaux; }

    /** Session d'origine du jeton ; {@code null} hors requête par jeton (tests). */
    public UUID getSessionId() { return sessionId; }

    /** Au moins un rôle, quelle qu'en soit la portée : sinon, page d'accueil vide. */
    public boolean aUnRole() { return !roles.isEmpty(); }

    /** Rôle détenu en portée globale. */
    public boolean aLeRole(String code) { return rolesGlobaux.contains(code); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return rolesGlobaux.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    /** L'identifiant d'annuaire ({@code sAMAccountName}). */
    @Override
    public String getUsername() {
        return identifiant;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
