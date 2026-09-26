package com.ipt.ged.security;

import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.Utilisateur;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L'appelant authentifié, tel que la chaîne de sécurité le manipule.
 *
 * <p>Instantané immuable, reconstruit à chaque requête depuis la base : les rôles
 * GED sont relus à chaque appel (dossier technique §3.3), jamais tirés du jeton.
 * Les autorités exposées sont {@code ROLE_<code>} pour chaque rôle GED ; une
 * identité fraîchement provisionnée n'en a aucune.
 *
 * <p><b>Aucun mot de passe</b> : {@link #getPassword()} renvoie {@code null}, la
 * vérification étant déléguée à l'annuaire.
 */
public class UtilisateurConnecte implements UserDetails {

    private final UUID utilisateurId;
    private final String identifiant;
    private final UUID employeId;
    private final String nomComplet;
    private final Set<String> roles;
    private final UUID sessionId;

    public UtilisateurConnecte(UUID utilisateurId, String identifiant, UUID employeId, String nomComplet,
                               Set<String> roles, UUID sessionId) {
        this.utilisateurId = utilisateurId;
        this.identifiant = identifiant;
        this.employeId = employeId;
        this.nomComplet = nomComplet;
        this.roles = Set.copyOf(roles);
        this.sessionId = sessionId;
    }

    /** Instantané d'une identité chargée (rôles compris). */
    public static UtilisateurConnecte depuis(Utilisateur u, UUID sessionId) {
        return new UtilisateurConnecte(u.getId(), u.getIdentifiant(), u.getEmploye().getId(),
                u.getEmploye().getFullName(),
                u.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()), sessionId);
    }

    public UUID getUtilisateurId() { return utilisateurId; }

    /** La personne métier : c'est elle qui dépose, possède un dossier, signe. */
    public UUID getEmployeId() { return employeId; }

    public String getNomComplet() { return nomComplet; }

    public Set<String> getRoles() { return roles; }

    /** Session d'origine du jeton ; {@code null} hors requête par jeton (tests). */
    public UUID getSessionId() { return sessionId; }

    public boolean aUnRole() { return !roles.isEmpty(); }

    public boolean aLeRole(String code) { return roles.contains(code); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
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
