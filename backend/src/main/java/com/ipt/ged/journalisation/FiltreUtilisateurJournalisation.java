package com.ipt.ged.journalisation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Renseigne {@code username} dans le MDC une fois l'appelant reconnu
 * (DAT 7.3 : obligatoire pour toute requête authentifiée).
 *
 * <p>Placé juste <b>après</b> la chaîne Spring Security : c'est elle qui établit
 * l'identité, quel que soit le mécanisme (jeton aujourd'hui, annuaire en E2,
 * clé d'API en E9). Le nom retenu est {@link Authentication#getName()}, commun
 * à tous ces mécanismes ; ce filtre n'a donc pas à changer quand
 * l'authentification change.
 *
 * <p>Le nettoyage du MDC reste à la charge de {@link FiltreContexteRequete},
 * qui enveloppe celui-ci.
 */
public class FiltreUtilisateurJournalisation extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete,
                                    @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getName() != null && !auth.getName().isBlank()) {
            MDC.put(ContexteJournalisation.USERNAME, auth.getName());
            // Conservé pour les passages suivants de la requête (rendu d'erreur,
            // dispatch asynchrone), où le contexte de sécurité n'est plus garni.
            requete.setAttribute(ContexteJournalisation.ATTRIBUT_USERNAME, auth.getName());
        }
        suite.doFilter(requete, reponse);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
