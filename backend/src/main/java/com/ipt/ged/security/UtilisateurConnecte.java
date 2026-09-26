package com.ipt.ged.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * L'utilisateur tel que Spring Security le manipule.
 *
 * <p><b>Aucune habilitation.</b> L'application n'a qu'un seul utilisateur,
 * l'administrateur : il n'y a plus rien à distinguer entre appelants, donc plus
 * de rôles ni de permissions nommées à porter. {@link #getAuthorities()} renvoie
 * délibérément une liste vide.
 *
 * <p>Ce que cette classe continue de faire est l'essentiel : elle porte
 * l'identité (e-mail, empreinte du mot de passe, compte actif ou non) que la
 * chaîne d'authentification vérifie, et l'{@code employeId} qui sert à TRACER
 * l'auteur d'un dépôt ou le signataire d'une étape — pas à l'autoriser.
 */
public class UtilisateurConnecte implements UserDetails {

    private final CompteUtilisateur compte;

    public UtilisateurConnecte(CompteUtilisateur compte) {
        this.compte = compte;
    }

    public CompteUtilisateur getCompte() {
        return compte;
    }

    public UUID getEmployeId() {
        return compte.getEmploye().getId();
    }

    /** Aucune : l'autorisation par rôle n'existe plus (utilisateur unique). */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** L'empreinte BCrypt : Spring la compare, elle ne sort jamais d'ici. */
    @Override
    public String getPassword() {
        return compte.getMotDePasse();
    }

    @Override
    public String getUsername() {
        return compte.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return compte.isActif();
    }
}
