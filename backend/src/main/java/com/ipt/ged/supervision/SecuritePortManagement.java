package com.ipt.ged.supervision;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accès au port de management (Actuator) : ouvert sans jeton, parce que la
 * restriction est RÉSEAU (DAT 6.2.3 A05, 6.7) — le port est lié à l'interface
 * interne ({@code management.server.address}) et NGINX ne le relaie pas.
 *
 * <p>Pourquoi une chaîne dédiée : Spring Boot applique la chaîne de sécurité de
 * l'application au port de management aussi. Sans cette chaîne, la règle
 * générale « toute requête authentifiée » exigerait un jeton utilisateur du
 * serveur Prometheus, ce qui n'a pas de sens pour un collecteur technique.
 *
 * <p>La chaîne ne s'applique qu'aux requêtes arrivées sur le port de
 * management effectif (relevé au démarrage de son serveur) : l'API publique
 * n'est en rien affectée, et si le management partage le port de l'API, cette
 * chaîne ne s'applique jamais et l'Actuator reste derrière l'authentification.
 * Seuls {@code health} et {@code prometheus} y sont exposés
 * ({@code management.endpoints.web.exposure.include}).
 */
@Configuration
public class SecuritePortManagement {

    /** Espace de noms Spring Boot du serveur de management. */
    static final String ESPACE_MANAGEMENT = "management";

    private final AtomicInteger portManagement = new AtomicInteger(-1);

    @EventListener
    public void serveurDemarre(WebServerInitializedEvent evenement) {
        if (ESPACE_MANAGEMENT.equals(evenement.getApplicationContext().getServerNamespace())) {
            portManagement.set(evenement.getWebServer().getPort());
        }
    }

    boolean surPortManagement(HttpServletRequest requete) {
        int port = portManagement.get();
        return port > 0 && requete.getLocalPort() == port;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain chaineManagement(HttpSecurity http) throws Exception {
        http.securityMatcher(this::surPortManagement)
                // Lecture seule, aucun cookie : pas de vecteur CSRF.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
