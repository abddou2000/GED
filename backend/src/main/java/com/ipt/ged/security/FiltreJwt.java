package com.ipt.ged.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reconnaît l'appelant à partir de l'en-tête {@code Authorization: Bearer …}.
 *
 * <p>Le filtre n'interdit rien : il se contente de renseigner le contexte de
 * sécurité quand le jeton est valide. C'est la chaîne de filtres qui décide
 * ensuite si la route demandée exige une authentification. Un jeton absent ou
 * invalide laisse donc simplement l'appelant anonyme.
 *
 * <p>Le compte est relu en base à chaque requête (via
 * {@link ServiceUtilisateurs}) : une désactivation prend effet tout de suite,
 * sans attendre l'expiration du jeton.
 */
@Component
public class FiltreJwt extends OncePerRequestFilter {

    private static final String ENTETE = "Authorization";
    private static final String PREFIXE = "Bearer ";

    private final ServiceJeton jetons;
    private final ServiceUtilisateurs utilisateurs;

    public FiltreJwt(ServiceJeton jetons, ServiceUtilisateurs utilisateurs) {
        this.jetons = jetons;
        this.utilisateurs = utilisateurs;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete,
                                    @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite)
            throws ServletException, IOException {

        String jeton = extraire(requete);
        if (jeton != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = jetons.emailDe(jeton);
            if (email != null) {
                try {
                    UserDetails details = utilisateurs.loadUserByUsername(email);
                    if (details.isEnabled()) {
                        var auth = new UsernamePasswordAuthenticationToken(
                                details, null, details.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(requete));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (UsernameNotFoundException e) {
                    // Jeton signé par nous mais dont le compte a disparu depuis :
                    // l'appelant reste anonyme, la route protégée répondra 401.
                    logger.debug("Compte du jeton introuvable : " + email);
                }
            }
        }
        suite.doFilter(requete, reponse);
    }

    private String extraire(HttpServletRequest requete) {
        String brut = requete.getHeader(ENTETE);
        if (brut == null || !brut.startsWith(PREFIXE)) return null;
        String valeur = brut.substring(PREFIXE.length()).trim();
        return valeur.isEmpty() ? null : valeur;
    }
}
