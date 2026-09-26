package com.ipt.ged.journalisation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Alimente le MDC de chaque requête avec {@code ip}, {@code traceId} et
 * {@code spanId} (DAT 7.1), et pose {@code username} à « - » en attendant que
 * {@link FiltreUtilisateurJournalisation} le remplace une fois l'appelant
 * authentifié.
 *
 * <p>Placé en tête de la chaîne, avant Spring Security : les journaux de
 * l'authentification elle-même (jeton refusé, compte désactivé) portent ainsi
 * déjà l'adresse et la trace de la requête.
 *
 * <h2>Adresse IP</h2>
 * <p>Derrière NGINX, {@code getRemoteAddr()} vaut l'adresse du proxy. L'adresse
 * du client est lue dans {@code X-Forwarded-For} <b>seulement si la requête
 * vient d'un proxy déclaré de confiance</b> : sinon n'importe quel client
 * écrirait l'adresse de son choix dans le journal. On prend la dernière entrée
 * de l'en-tête, celle qu'ajoute le proxy lui-même ; les précédentes viennent du
 * client et ne prouvent rien.
 */
public class FiltreContexteRequete extends OncePerRequestFilter {

    static final String ENTETE_TRACEPARENT = "traceparent";
    static final String ENTETE_XFF = "X-Forwarded-For";

    private final List<IpAddressMatcher> proxysDeConfiance;

    /**
     * @param proxysDeConfiance adresses ou plages CIDR des proxys dont on croit
     *                          l'en-tête {@code X-Forwarded-For} (NGINX)
     */
    public FiltreContexteRequete(List<String> proxysDeConfiance) {
        this.proxysDeConfiance = proxysDeConfiance.stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest requete,
                                    @NonNull HttpServletResponse reponse,
                                    @NonNull FilterChain suite) throws ServletException, IOException {
        /* Une même requête repasse ici lors d'un dispatch asynchrone ou du rendu
           d'erreur : on réutilise alors le contexte déjà établi, sans quoi ces
           journaux changeraient de traceId en cours de route. */
        TraceW3C trace = (TraceW3C) requete.getAttribute(ContexteJournalisation.ATTRIBUT_TRACE);
        if (trace == null) {
            trace = TraceW3C.depuisEntete(requete.getHeader(ENTETE_TRACEPARENT));
            requete.setAttribute(ContexteJournalisation.ATTRIBUT_TRACE, trace);
        }
        String ip = (String) requete.getAttribute(ContexteJournalisation.ATTRIBUT_IP);
        if (ip == null) {
            ip = adresseClient(requete);
            requete.setAttribute(ContexteJournalisation.ATTRIBUT_IP, ip);
        }
        Object username = requete.getAttribute(ContexteJournalisation.ATTRIBUT_USERNAME);

        MDC.put(ContexteJournalisation.TRACE_ID, trace.traceId());
        MDC.put(ContexteJournalisation.SPAN_ID, trace.spanId());
        MDC.put(ContexteJournalisation.IP, ip);
        MDC.put(ContexteJournalisation.USERNAME,
                username != null ? username.toString() : ContexteJournalisation.ANONYME);
        try {
            suite.doFilter(requete, reponse);
        } finally {
            /* Les threads de Tomcat sont recyclés : sans ce nettoyage, la requête
               suivante servie par ce thread hériterait de l'identité de celle-ci
               dans ses journaux. */
            MDC.remove(ContexteJournalisation.TRACE_ID);
            MDC.remove(ContexteJournalisation.SPAN_ID);
            MDC.remove(ContexteJournalisation.IP);
            MDC.remove(ContexteJournalisation.USERNAME);
        }
    }

    String adresseClient(HttpServletRequest requete) {
        String distante = requete.getRemoteAddr();
        String xff = requete.getHeader(ENTETE_XFF);
        if (xff == null || xff.isBlank() || !deConfiance(distante)) return distante;
        String[] entrees = xff.split(",");
        String derniere = entrees[entrees.length - 1].trim();
        return estAdresseIp(derniere) ? derniere : distante;
    }

    private boolean deConfiance(String adresse) {
        if (adresse == null) return false;
        for (IpAddressMatcher m : proxysDeConfiance) {
            try {
                if (m.matches(adresse)) return true;
            } catch (IllegalArgumentException e) {
                // Adresse illisible : pas de confiance.
            }
        }
        return false;
    }

    /* Une valeur qui n'est pas une adresse (texte injecté, retour à la ligne)
       n'entre pas dans le journal : on retombe sur l'adresse du proxy. */
    private static boolean estAdresseIp(String valeur) {
        if (valeur.isEmpty() || valeur.length() > 45) return false;
        for (int i = 0; i < valeur.length(); i++) {
            char c = valeur.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != '.' && c != ':') return false;
        }
        return true;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
