package com.ipt.ged.config;

import com.ipt.ged.security.FiltreJwt;
import com.ipt.ged.security.ServiceUtilisateurs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Sécurité de l'API : authentification par jeton JWT, API sans état.
 *
 * <p>Tout est fermé par défaut ({@code anyRequest().authenticated()}). Seules
 * les routes explicitement listées restent ouvertes. C'est le sens de lecture
 * le plus sûr : oublier de protéger un nouveau contrôleur ne l'expose pas, il
 * faut au contraire une décision explicite pour l'ouvrir.
 *
 * <p>Choix notables :
 * <ul>
 *   <li><b>Sans état</b> : aucune session serveur, donc aucun cookie de session
 *       — et par conséquent aucune surface CSRF, ce qui justifie de désactiver
 *       la protection correspondante. Avec un cookie, la désactiver serait une
 *       faute.</li>
 *   <li><b>401 plutôt qu'une redirection</b> : un appel d'API non authentifié
 *       doit recevoir un code, pas une page de connexion en HTML que le
 *       frontend prendrait pour une réponse valide.</li>
 *   <li><b>Aucune autorisation par rôle</b> : l'application n'a qu'un seul
 *       utilisateur. La seule question posée est « l'appelant est-il
 *       authentifié ? », et elle l'est ici, dans la chaîne de filtres.</li>
 * </ul>
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

        /* Le repli localhost est commode en développement, mais en production
           il signifie qu'on a oublié GED_ORIGINES : le frontend réel serait
           alors refusé, et surtout la liste blanche ne reflète plus le
           déploiement. On le signale bruyamment plutôt que de le laisser
           passer inaperçu. Simple avertissement et non échec : contrairement à
           la clé de signature, une liste CORS trop étroite ne permet à
           personne de se faire passer pour un administrateur. */
        boolean prod = Arrays.asList(environnement.getActiveProfiles()).contains("prod");
        if (prod && origines.contains("localhost")) {
            log.warn("Profil prod avec des origines CORS locales ({}). "
                    + "Définissez GED_ORIGINES avec les domaines réels du frontend.", origines);
        }
    }

    /**
     * Chaîne de filtres.
     *
     * <p><b>Swagger n'est ouvert qu'en dehors de la production.</b> La
     * documentation était accessible sans jeton, y compris sur une instance
     * exposée : {@code GET /v3/api-docs} renvoyait 58 Ko décrivant les 111
     * opérations de l'API — chemins, verbes, forme des corps, noms de champs.
     * Ce n'est pas une fuite de données, c'est le plan du bâtiment offert avant
     * l'effraction. Et rien ne permettait de le couper : l'autorisation était
     * écrite en dur.</p>
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, FiltreJwt filtreJwt,
                                           Environment environnement) throws Exception {
        boolean horsProduction = !Arrays.asList(environnement.getActiveProfiles()).contains("prod");
        http
                // Pas de cookie de session, donc pas de vecteur CSRF à couvrir.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Le pré-vol CORS ne porte aucun en-tête d'autorisation.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Connexion : seule porte ouverte, par définition.
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        /* Le rendu d'erreur de Spring Boot passe par un dispatch
                           interne vers /error. Ce second passage n'a plus de
                           jeton dans son contexte : sans cette autorisation, un
                           404 ou un 500 survenu APRÈS authentification ressort
                           en 401 et masque la vraie cause. */
                        .requestMatchers("/error").permitAll()
                        /* Sonde de vie : ouverte partout, y compris en production.
                           Un orchestrateur ou un répartiteur de charge interroge
                           cette route sans pouvoir s'authentifier ; elle ne
                           renvoie qu'un état, jamais de donnée métier. */
                        .requestMatchers("/actuator/health", "/api/v1/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                            .access((appelant, contexte) ->
                                    new org.springframework.security.authorization.AuthorizationDecision(horsProduction))
                        .anyRequest().authenticated())
                .addFilterBefore(filtreJwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt, coût 12. Le coût par défaut (10) est daté ; 12 reste
     * imperceptible à la connexion tout en multipliant par quatre l'effort
     * d'une attaque par force brute sur une base dérobée.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Gestionnaire d'authentification construit explicitement.
     *
     * <p>Passer par {@code AuthenticationConfiguration} aurait marché, mais en
     * s'appuyant sur la découverte automatique du {@code UserDetailsService} et
     * de l'{@code AuthenticationProvider} présents dans le contexte. Le
     * câblage direct rend la chaîne lisible et ne dépend d'aucun ordre
     * d'initialisation : un seul fournisseur, celui qu'on a choisi.
     */
    @Bean
    public AuthenticationManager authenticationManager(ServiceUtilisateurs utilisateurs,
                                                       PasswordEncoder encodeur) {
        DaoAuthenticationProvider fournisseur = new DaoAuthenticationProvider();
        fournisseur.setUserDetailsService(utilisateurs);
        fournisseur.setPasswordEncoder(encodeur);
        // Un e-mail inconnu doit échouer exactement comme un mot de passe faux,
        // sans quoi la différence de réponse permet d'énumérer les comptes.
        fournisseur.setHideUserNotFoundExceptions(true);
        return new ProviderManager(fournisseur);
    }

    /** Origines du frontend autorisées à appeler l'API. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(origines.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Le jeton voyage dans un en-tête, pas dans un cookie : inutile
        // d'autoriser l'envoi d'identifiants d'origine croisée.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
