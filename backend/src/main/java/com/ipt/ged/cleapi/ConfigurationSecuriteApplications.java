package com.ipt.ged.cleapi;

import com.ipt.ged.audit.AuditService;
import com.ipt.ged.common.erreur.ReponsesSecuriteProblem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.util.List;

/**
 * Chaîne de sécurité des applications clientes (DAT §5.2, §5.4) : toute
 * requête vers {@code /api/**} qui présente {@code X-API-Key} y passe, et
 * seulement celles-là.
 *
 * <p>Pourquoi une chaîne distincte plutôt qu'un filtre ajouté à
 * {@code SecurityConfig} : le flux des utilisateurs (lot identité) reste
 * intact, sans qu'aucun de ses réglages (jeton, cookie de renouvellement,
 * CSRF) ne s'applique à un appel serveur à serveur, ni l'inverse. Une requête
 * qui porte à la fois une clé et un jeton est traitée comme un appel
 * d'application : le jeton est ignoré.
 *
 * <p>Ordre : après la chaîne du port de management, avant la chaîne des
 * utilisateurs (sans ordre explicite, donc la dernière). Aucune modification
 * de {@code SecurityConfig} n'est nécessaire ; celui-ci doit seulement ne pas
 * déclarer d'ordre plus prioritaire que {@code HIGHEST_PRECEDENCE + 10}.
 *
 * <p>Une application ne peut pas administrer les clés, les applications ni
 * consulter le journal d'audit, ni lire des notifications : ces routes lui
 * sont refusées ici, quel que soit le reste de sa portée.
 */
@Configuration
public class ConfigurationSecuriteApplications {

    public static final int ORDRE = Ordered.HIGHEST_PRECEDENCE + 10;

    /** Routes réservées aux utilisateurs : refusées à toute application (aussi lues par la spécification OpenAPI). */
    public static final List<String> CHEMINS_RESERVES_UTILISATEURS = List.of("/api/v1/applications/**",
            "/api/v1/cles-api/**", "/api/v1/audit/**", "/api/v1/auth/**", "/api/v1/notifications/**",
            "/api/v1/admin/**", "/api/v1/access-groups/**");

    @Bean
    @Order(ORDRE)
    public SecurityFilterChain chaineApplications(HttpSecurity http, AuthentificationCleApi authentification,
                                                  ResolveurIdentiteDeleguee delegation,
                                                  ReponsesSecuriteProblem reponses, AuditService audit,
                                                  MeterRegistry metriques)
            throws Exception {
        // Instancié ici et non déclaré en bean : Spring Boot inscrirait sinon
        // le filtre dans la chaîne des servlets, pour toutes les requêtes.
        FiltreCleApi filtre = new FiltreCleApi(authentification, delegation, reponses, audit, metriques);
        http.securityMatcher(requete -> requete.getHeader(FiltreCleApi.ENTETE_CLE) != null
                        && requete.getRequestURI().startsWith("/api/"))
                // Appel serveur à serveur, sans cookie : pas de vecteur CSRF.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(reponses).accessDeniedHandler(reponses))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(CHEMINS_RESERVES_UTILISATEURS.toArray(String[]::new)).denyAll()
                        .anyRequest().hasAuthority(ApplicationAuthentifiee.AUTORITE))
                .addFilterBefore(filtre, AuthorizationFilter.class);
        return http.build();
    }
}
