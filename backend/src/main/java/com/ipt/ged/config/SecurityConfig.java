package com.ipt.ged.config;

import com.ipt.ged.identite.Role;
import com.ipt.ged.security.FiltreJwt;
import com.ipt.ged.security.UtilisateurConnecte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sécurité de l'API : jeton d'accès RS256 dans l'en-tête {@code Authorization},
 * API sans état (dossier technique §3.4.1, §8.2.1).
 *
 * <p>Tout est fermé par défaut. Trois niveaux :
 * <ul>
 *   <li><b>ouvert</b> : connexion, renouvellement et déconnexion (fondés sur le
 *       cookie de renouvellement, protégés par un en-tête personnalisé exigé par
 *       le contrôleur), rendu d'erreur, sonde de santé, documentation hors
 *       production ;</li>
 *   <li><b>authentifié</b> : {@code /api/v1/auth/me} — une identité provisionnée
 *       sans rôle doit pouvoir savoir qui elle est et afficher sa page d'accueil
 *       vide (§3.4.2) ;</li>
 *   <li><b>au moins un rôle GED</b> : tout le reste. Une identité sans rôle
 *       n'accède à rien (§3.2 : l'attribution d'un rôle est un acte manuel de
 *       l'Administrateur). {@code /api/v1/admin/**} exige le rôle
 *       Administrateur.</li>
 * </ul>
 * Les autorisations fines par nœud et par permission arrivent au lot E3.
 *
 * <p>Pas de protection CSRF de Spring : l'API n'accepte le jeton d'accès que
 * dans l'en-tête {@code Authorization}, qu'un site tiers ne peut pas poser ; le
 * seul cookie (renouvellement) est {@code SameSite=Strict}, limité au chemin
 * {@code /api/v1/auth}, et son usage exige l'en-tête {@code X-GED-Renouvellement}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Origines autorisées à appeler l'API, séparées par des virgules. */
    private final String origines;

    public SecurityConfig(
            @Value("${ged.securite.origines:http://localhost:*,http://127.0.0.1:*}") String origines,
            Environment environnement) {
        this.origines = origines;
        if (exploitation(environnement) && origines.contains("localhost")) {
            log.warn("Profil prod ou uat avec des origines CORS locales ({}). "
                    + "Définissez GED_ORIGINES avec les domaines réels du frontend.", origines);
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, FiltreJwt filtreJwt,
                                           Environment environnement) throws Exception {
        // Documentation de l'API fermée en production ET en recette (uat), qui
        // partagent les mêmes contrôles de sécurité.
        boolean horsProduction = !exploitation(environnement);
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        /* Le rendu d'erreur de Spring Boot passe par un dispatch
                           interne vers /error, sans jeton dans son contexte. */
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/api/v1/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                            .access((appelant, contexte) -> new AuthorizationDecision(horsProduction))
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.ADMINISTRATEUR)
                        .anyRequest().access(auMoinsUnRole()))
                .addFilterBefore(filtreJwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Profils d'exploitation : prod et uat appliquent les mêmes contrôles de sécurité. */
    static boolean exploitation(Environment environnement) {
        return Arrays.stream(environnement.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("uat"));
    }

    /**
     * Authentifié ET porteur d'au moins un rôle GED. Une identité tout juste
     * provisionnée n'en a aucun : elle reçoit 403 partout ailleurs que sur
     * {@code /auth/me}.
     */
    static AuthorizationManager<RequestAuthorizationContext> auMoinsUnRole() {
        return (Supplier<Authentication> appelant, RequestAuthorizationContext contexte) -> {
            Authentication a = appelant.get();
            // Seul un principal GED compte : un appelant anonyme porte lui
            // aussi une autorité « ROLE_ANONYMOUS », qui ne vaut rien ici.
            boolean ok = a != null && a.getPrincipal() instanceof UtilisateurConnecte u && u.aUnRole();
            return new AuthorizationDecision(ok);
        };
    }

    /**
     * Aucun gestionnaire d'authentification par mot de passe dans la GED : la
     * seule vérification d'identité est la liaison à l'annuaire
     * ({@code /api/v1/auth/login}). Déclarer ce gestionnaire qui refuse tout
     * empêche aussi Spring Boot de créer son utilisateur en mémoire à mot de
     * passe généré.
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        return authentification -> {
            throw new ProviderNotFoundException("Authentification par l'annuaire uniquement (/api/v1/auth/login).");
        };
    }

    /** Origines du frontend autorisées à appeler l'API. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(origines.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        /* Le frontend est servi par la même origine que l'API (NGINX, proxy de
           développement) : le cookie de renouvellement n'a pas à franchir une
           frontière d'origine. On ne l'autorise donc pas. */
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
