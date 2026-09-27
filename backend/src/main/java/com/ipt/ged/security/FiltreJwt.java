package com.ipt.ged.security;

import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.session.ServiceSessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Reconnaît l'appelant à partir de l'en-tête {@code Authorization: Bearer …}
 * — et de lui seul : l'API n'accepte jamais le jeton d'accès dans un cookie ni
 * un paramètre (protection CSRF, §3.4.1).
 *
 * <p>Le filtre n'interdit rien : il renseigne le contexte de sécurité quand le
 * jeton est valable, la chaîne décide ensuite. Pour qu'il le soit :
 * <ol>
 *   <li>signature RS256, émetteur, expiration (15 min) — {@link ServiceJeton} ;</li>
 *   <li>la session d'origine ({@code sid}) est toujours ouverte : une
 *       déconnexion ou une révocation par l'Administrateur prend effet sur la
 *       requête suivante, sans attendre l'expiration du jeton ;</li>
 *   <li>l'identité existe ; ses rôles sont relus en base à chaque requête.</li>
 * </ol>
 */
@Component
public class FiltreJwt extends OncePerRequestFilter {

    private static final String ENTETE = "Authorization";
    private static final String PREFIXE = "Bearer ";

    private final ServiceJeton jetons;
    private final ServiceSessions sessions;
    private final ServiceIdentites identites;

    public FiltreJwt(ServiceJeton jetons, ServiceSessions sessions, ServiceIdentites identites) {
        this.jetons = jetons;
        this.sessions = sessions;
        this.identites = identites;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete,
                                    @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite)
            throws ServletException, IOException {

        String jeton = extraire(requete);
        if (jeton == null) {
            suite.doFilter(requete, reponse);
            return;
        }
        /* Un jeton présent est TOUJOURS évalué, et l'identité qu'il établit ne
           survit pas à la requête : un contexte hérité (fil réutilisé, contexte
           de test) ne doit jamais faire passer un jeton révoqué. Jeton invalide
           = appelant anonyme. */
        Optional<UtilisateurConnecte> principal = jetons.lire(jeton)
                .filter(j -> sessions.active(j.sessionId()))
                .flatMap(j -> identites.principal(j.utilisateurId(), j.sessionId()));
        SecurityContextHolder.clearContext();
        principal.ifPresent(p -> {
            var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(requete));
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
        try {
            suite.doFilter(requete, reponse);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String extraire(HttpServletRequest requete) {
        String brut = requete.getHeader(ENTETE);
        if (brut == null || !brut.startsWith(PREFIXE)) return null;
        String valeur = brut.substring(PREFIXE.length()).trim();
        return valeur.isEmpty() ? null : valeur;
    }
}
