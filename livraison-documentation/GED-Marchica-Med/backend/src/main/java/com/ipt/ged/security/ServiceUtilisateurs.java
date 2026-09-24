package com.ipt.ged.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Résolution du compte à partir de l'e-mail saisi.
 *
 * <p>Le message d'erreur ne dit jamais si c'est l'e-mail ou le mot de passe qui
 * est faux : distinguer les deux permettrait d'énumérer les comptes existants.
 */
@Service
public class ServiceUtilisateurs implements UserDetailsService {

    private final CompteUtilisateurRepository comptes;

    public ServiceUtilisateurs(CompteUtilisateurRepository comptes) {
        this.comptes = comptes;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return comptes.findByEmailIgnoreCase(email)
                .map(UtilisateurConnecte::new)
                .orElseThrow(() -> new UsernameNotFoundException("Identifiants invalides"));
    }
}
